package com.model.documindai.service;

import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.model.SourceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class DocumentSummaryService {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentSummaryService.class);

    private static final String PROMPT_PREFIX = """
            You are summarizing one uploaded PDF.
            Use only the supplied document text.
            Do not invent facts, dates, figures, conclusions, or sections.
            Identify the subject and purpose, major sections, key concepts,
            important facts, dates, figures, and conclusions.
            Preserve page references from the [chunk ... pages ...] labels
            when making claims. State when information is unclear.
            Avoid repetition and do not mention similarity scores.

            %s

            DOCUMENT SECTIONS:
            """;
    private static final String PROMPT_SUFFIX = """

            SUMMARY:
            """;

    private final DocumentService documentService;
    private final GeminiModelService geminiModelService;
    private final int inputCharacterBudget;
    private final int outputCharacterAllowance;
    private final int maximumReductionDepth;
    private final double minimumProgressRatio;
    private final long maximumDurationMillis;
    private final int maximumGeminiCalls;
    private final ReductionBudget reductionBudget;

    public DocumentSummaryService(
            DocumentService documentService,
            GeminiModelService geminiModelService,
            int inputCharacterBudget) {
        this(
                documentService,
                geminiModelService,
                inputCharacterBudget,
                Math.max(200, inputCharacterBudget / 4),
                8,
                0.05,
                120_000L,
                100,
                true,
                new ReductionBudget()
        );
    }

    public DocumentSummaryService(
            DocumentService documentService,
            GeminiModelService geminiModelService,
            int inputCharacterBudget,
            int outputCharacterAllowance,
            int maximumReductionDepth,
            double minimumProgressRatio) {
        this(
                documentService,
                geminiModelService,
                inputCharacterBudget,
                outputCharacterAllowance,
                maximumReductionDepth,
                minimumProgressRatio,
                120_000L,
                100,
                true,
                new ReductionBudget()
        );
    }

    public DocumentSummaryService(
            DocumentService documentService,
            GeminiModelService geminiModelService,
            int inputCharacterBudget,
            int outputCharacterAllowance,
            int maximumReductionDepth,
            double minimumProgressRatio,
            long maximumDurationMillis,
            int maximumGeminiCalls) {
        this(
                documentService,
                geminiModelService,
                inputCharacterBudget,
                outputCharacterAllowance,
                maximumReductionDepth,
                minimumProgressRatio,
                maximumDurationMillis,
                maximumGeminiCalls,
                true,
                new ReductionBudget()
        );
    }

    @Autowired
    public DocumentSummaryService(
            DocumentService documentService,
            GeminiModelService geminiModelService,
            @Value("${documind.summarization.input-character-budget:12000}")
            int inputCharacterBudget,
            @Value("${documind.summarization.output-character-allowance:2000}")
            int outputCharacterAllowance,
            @Value("${documind.summarization.maximum-reduction-depth:8}")
            int maximumReductionDepth,
            @Value("${documind.summarization.minimum-progress-ratio:0.05}")
            double minimumProgressRatio,
            @Value("${documind.summarization.maximum-duration-millis:120000}")
            long maximumDurationMillis,
            @Value("${documind.summarization.maximum-gemini-calls:100}")
            Integer maximumGeminiCalls,
            ReductionBudget reductionBudget) {

        this(
                documentService,
                geminiModelService,
                inputCharacterBudget,
                outputCharacterAllowance,
                maximumReductionDepth,
                minimumProgressRatio,
                maximumDurationMillis,
                maximumGeminiCalls.intValue(),
                true,
                reductionBudget
        );
    }

    private DocumentSummaryService(
            DocumentService documentService,
            GeminiModelService geminiModelService,
            int inputCharacterBudget,
            int outputCharacterAllowance,
            int maximumReductionDepth,
            double minimumProgressRatio,
            long maximumDurationMillis,
            int maximumGeminiCalls,
            boolean validatedConstructor,
            ReductionBudget reductionBudget) {

        if (inputCharacterBudget < 1000
                || outputCharacterAllowance < 100
                || outputCharacterAllowance >= inputCharacterBudget
                || maximumReductionDepth < 0
                || minimumProgressRatio <= 0.0
                || minimumProgressRatio >= 1.0
                || maximumDurationMillis < 1
                || maximumGeminiCalls < 1) {
            throw new IllegalArgumentException(
                    "Invalid summarization budget or reduction settings."
            );
        }

        this.documentService = documentService;
        this.geminiModelService = geminiModelService;
        this.inputCharacterBudget = inputCharacterBudget;
        this.outputCharacterAllowance = outputCharacterAllowance;
        this.maximumReductionDepth = maximumReductionDepth;
        this.minimumProgressRatio = minimumProgressRatio;
        this.maximumDurationMillis = maximumDurationMillis;
        this.maximumGeminiCalls = maximumGeminiCalls;
        this.reductionBudget = java.util.Objects.requireNonNull(
                reductionBudget,
                "reductionBudget must not be null"
        );
    }

    public void streamSummary(
            Long documentId,
            Consumer<String> onToken,
            Consumer<List<SourceResult>> onSources,
            Consumer<Throwable> onError,
            Runnable onComplete) {
        streamSummary(
                documentId,
                onToken,
                onSources,
                onError,
                onComplete,
                () -> false
        );
    }

    public void streamSummary(
            Long documentId,
            Consumer<String> onToken,
            Consumer<List<SourceResult>> onSources,
            Consumer<Throwable> onError,
            Runnable onComplete,
            BooleanSupplier cancelled) {

        try {
            long deadlineNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    maximumDurationMillis
            );
            int[] geminiCalls = {0};
            List<DocumentChunk> chunks =
                    documentService.getAllChunks(documentId);
            ensureDeadline(deadlineNanos);
            ensureNotCancelled(cancelled);

            Map<String, SourceResult> sourceByIdentity = new LinkedHashMap<>();
            for (DocumentChunk chunk : chunks) {
                String chunkId = String.valueOf(chunk.getId());
                String chunkNumber = String.valueOf(chunk.getChunkNumber());
                String identity = documentId + ":" + chunkId + ":"
                        + chunkNumber;
                sourceByIdentity.putIfAbsent(
                        identity,
                        new SourceResult(
                                String.valueOf(documentId),
                                chunkId,
                                chunkNumber,
                                chunk.getPageNumbers().stream().toList()
                        )
                );
            }

            onSources.accept(List.copyOf(sourceByIdentity.values()));
            ensureNotCancelled(cancelled);

            if (chunks.isEmpty()) {
                throw new IllegalStateException(
                        "The selected document has no readable chunks."
                );
            }

            List<SummaryPart> parts = chunks.stream()
                    .flatMap(chunk -> splitChunk(chunk).stream())
                    .toList();

            if (parts.isEmpty()) {
                throw new IllegalStateException(
                        "The selected document has no readable text."
                );
            }

            String summary = reduce(
                    parts,
                    deadlineNanos,
                    geminiCalls,
                    cancelled,
                    null
            );
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException(
                        "The summarization model returned an empty result."
                );
            }

            ensureNotCancelled(cancelled);
            onToken.accept(summary);
            ensureNotCancelled(cancelled);
            onComplete.run();
        } catch (Throwable error) {
            onError.accept(error);
        }
    }

    /**
     * Summarizes one persisted worker batch using the same prompt, budget,
     * provenance, retry, and deadline rules as the synchronous pipeline.
     */
    public BatchSummary summarizeBatch(
            List<DocumentChunk> chunks,
            long deadlineNanos,
            BooleanSupplier cancelled) {
        return summarizeBatch(
                chunks,
                deadlineNanos,
                cancelled,
                null
        );
    }

    public BatchSummary summarizeBatch(
            List<DocumentChunk> chunks,
            long deadlineNanos,
            BooleanSupplier cancelled,
            GeminiCallReservation reservation) {
        ensureNotCancelled(cancelled);
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException(
                    "The summarization batch has no readable chunks."
            );
        }

        List<SummaryPart> parts = chunks.stream()
                .flatMap(chunk -> splitChunk(chunk).stream())
                .toList();
        if (parts.isEmpty()) {
            throw new IllegalStateException(
                    "The summarization batch has no readable text."
            );
        }

        int[] geminiCalls = {0};
        String summary = reduce(
                parts,
                deadlineNanos,
                geminiCalls,
                cancelled,
                reservation
        );
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException(
                    "The summarization model returned an empty result."
            );
        }

        Map<String, SourceResult> sources = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks) {
            String documentId = chunk.getDocument() == null
                    ? null
                    : String.valueOf(chunk.getDocument().getId());
            String identity = documentId + ":" + chunk.getId()
                    + ":" + chunk.getChunkNumber();
            Set<Integer> pageNumbers = chunk.getPageNumbers() == null
                    ? Set.of()
                    : chunk.getPageNumbers();
            sources.putIfAbsent(
                    identity,
                    new SourceResult(
                            documentId,
                            String.valueOf(chunk.getId()),
                            String.valueOf(chunk.getChunkNumber()),
                            pageNumbers.stream().toList()
                    )
            );
        }
        return new BatchSummary(
                summary,
                totalCharacters(parts),
                summary.length(),
                List.copyOf(sources.values()),
                geminiCalls[0]
        );
    }

    public record BatchSummary(
            String summary,
            int inputCharacterCount,
            int outputCharacterCount,
            List<SourceResult> sources,
            int geminiCallCount) {
    }

    private String reduce(
            List<SummaryPart> initialParts,
            long deadlineNanos,
            int[] geminiCalls,
            BooleanSupplier cancelled,
            GeminiCallReservation reservation) {
        List<SummaryPart> current = initialParts;

        for (int level = 0; level <= maximumReductionDepth; level++) {
            ensureNotCancelled(cancelled);
            List<List<SummaryPart>> groups = groupParts(current);

            if (groups.isEmpty()) {
                throw reductionFailure(level, "no parts remain");
            }

            int inputCharacters = totalCharacters(current);
            log.info(
                    "Document summarization level {}: parts={}, "
                            + "groups={}, inputCharacters={}",
                    level, current.size(), groups.size(), inputCharacters
            );

            if (groups.size() == 1) {
                String finalSummary = generateSummary(
                        groups.get(0),
                        true,
                        deadlineNanos,
                        geminiCalls,
                        cancelled,
                        reservation
                );
                log.info(
                        "Document summarization completed at level {}: "
                                + "outputCharacters={}",
                        level, finalSummary == null
                                ? 0
                                : finalSummary.length()
                );
                return finalSummary;
            }

            if (level == maximumReductionDepth) {
                throw reductionFailure(level, "maximum reduction depth reached");
            }

            List<SummaryPart> reduced = new ArrayList<>();
            for (List<SummaryPart> group : groups) {
                String summary = generateSummary(
                        group,
                        false,
                        deadlineNanos,
                        geminiCalls,
                        cancelled,
                        reservation
                );
                if (summary == null || summary.isBlank()) {
                    throw reductionFailure(
                            level,
                            "an intermediate summary was empty"
                    );
                }
                reduced.add(new SummaryPart(
                        provenance(group),
                        summary
                ));
            }

            int outputCharacters = totalCharacters(reduced);
            double progress =
                    inputCharacters == 0
                            ? 0.0
                            : (double) (inputCharacters - outputCharacters)
                            / inputCharacters;

            log.info(
                    "Document summarization reduction {}: outputParts={}, "
                            + "outputCharacters={}, progress={}",
                    level, reduced.size(), outputCharacters, progress
            );

            boolean sufficientSizeReduction =
                    progress >= minimumProgressRatio;

            if (outputCharacters >= inputCharacters
                    || !sufficientSizeReduction) {
                throw reductionFailure(
                        level,
                        "reduction did not meet the minimum progress ratio"
                );
            }

            current = reduced;
        }

        throw reductionFailure(
                maximumReductionDepth,
                "reduction loop terminated unexpectedly"
        );
    }

    private List<SummaryPart> splitChunk(DocumentChunk chunk) {
        String text = chunk.getChunkText() == null
                ? ""
                : chunk.getChunkText().trim();

        if (text.isEmpty()) {
            return List.of();
        }

        Provenance provenance = new Provenance(
                chunk.getDocument() == null
                        ? null
                        : chunk.getDocument().getId(),
                chunk.getId(),
                chunk.getChunkNumber(),
                chunk.getPageNumbers()
        );
        int contentBudget = reductionBudget.contentBudget(
                availableContextBudget()
        );
        List<SummaryPart> parts = new ArrayList<>();

        for (int start = 0; start < text.length(); start += contentBudget) {
            int end = Math.min(start + contentBudget, text.length());
            parts.add(new SummaryPart(
                    List.of(provenance),
                    text.substring(start, end)
            ));
        }

        return parts;
    }

    private List<List<SummaryPart>> groupParts(List<SummaryPart> parts) {
        List<List<SummaryPart>> groups = new ArrayList<>();
        List<SummaryPart> current = new ArrayList<>();
        int currentLength = 0;

        for (SummaryPart part : parts) {
            for (SummaryPart fittingPart : splitPart(part)) {
                int partLength = fittingPart.characterLength();

                if (!current.isEmpty()
                        && currentLength + partLength
                        > availableContextBudget()) {
                    groups.add(current);
                    current = new ArrayList<>();
                    currentLength = 0;
                }

                current.add(fittingPart);
                currentLength += partLength;
            }
        }

        if (!current.isEmpty()) {
            groups.add(current);
        }

        return groups;
    }

    private List<SummaryPart> splitPart(SummaryPart part) {
        int contentBudget = reductionBudget.contentBudget(
                availableContextBudget()
        );

        if (part.text().length() <= contentBudget) {
            return List.of(part);
        }

        List<SummaryPart> split = new ArrayList<>();
        for (int start = 0; start < part.text().length(); start += contentBudget) {
            int end = Math.min(start + contentBudget, part.text().length());
            split.add(new SummaryPart(
                    part.provenance(),
                    part.text().substring(start, end)
            ));
        }
        return split;
    }

    private String generateSummary(
            List<SummaryPart> parts,
            boolean finalSummary,
            long deadlineNanos,
            int[] geminiCalls,
            BooleanSupplier cancelled,
            GeminiCallReservation reservation) {

        ensureDeadline(deadlineNanos);
        ensureNotCancelled(cancelled);
        if (geminiCalls[0] >= maximumGeminiCalls) {
            throw new IllegalStateException(
                    "Summarization stopped: maximum Gemini call count reached."
            );
        }

        StringBuilder context = new StringBuilder();
        Map<String, List<Provenance>> provenanceByReference =
                new LinkedHashMap<>();
        int referenceNumber = 1;
        for (SummaryPart part : parts) {
            String reference = "S" + referenceNumber++;
            provenanceByReference.put(reference, part.provenance());
            context.append("[source ")
                    .append(reference)
                    .append("]")
                    .append("\n")
                    .append(part.text())
                    .append("\n\n");
        }

        String instruction = finalSummary
                ? "Create the final coherent summary."
                : "Create an accurate intermediate summary for these sections.";
        String prompt = PROMPT_PREFIX.formatted(instruction)
                + context
                + PROMPT_SUFFIX;

        if (prompt.length() > inputCharacterBudget - outputCharacterAllowance) {
            throw new IllegalStateException(
                    "Summarization prompt exceeds the configured safe input budget."
            );
        }

        geminiCalls[0]++;
        String summary = reservation == null
                ? geminiModelService.generateText(prompt, deadlineNanos)
                : geminiModelService.generateText(
                prompt,
                deadlineNanos,
                reservation
        );
        if (summary == null || summary.isBlank()) {
            throw reductionFailure(
                    0,
                    "Gemini returned an empty summary"
            );
        }
        return summary;
    }

    private void ensureDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw new IllegalStateException(
                    "Summarization stopped: overall deadline exceeded."
            );
        }
    }

    private void ensureNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new IllegalStateException(
                    "Summarization stopped because the client disconnected."
            );
        }
    }

    private int availableContextBudget() {
        int fixedPromptLength = PROMPT_PREFIX.formatted(
                "Create an accurate intermediate summary for these sections."
        ).length() + PROMPT_SUFFIX.length();

        return reductionBudget.availableContextBudget(
                inputCharacterBudget,
                outputCharacterAllowance,
                fixedPromptLength
        );
    }

    private int totalCharacters(List<SummaryPart> parts) {
        return parts.stream()
                .mapToInt(SummaryPart::characterLength)
                .sum();
    }

    private List<Provenance> provenance(List<SummaryPart> parts) {
        Map<String, Provenance> combined = new LinkedHashMap<>();
        for (SummaryPart part : parts) {
            for (Provenance provenance : part.provenance()) {
                combined.merge(
                        provenance.identity(),
                        provenance,
                        DocumentSummaryService::mergeProvenance
                );
            }
        }
        return List.copyOf(combined.values());
    }

    private static Provenance mergeProvenance(
            Provenance first,
            Provenance second) {
        Set<Integer> pages = new LinkedHashSet<>(first.pageNumbers());
        pages.addAll(second.pageNumbers());
        return new Provenance(
                first.documentId(),
                first.chunkId(),
                first.chunkNumber(),
                pages
        );
    }

    private IllegalStateException reductionFailure(
            int level,
            String reason) {
        return new IllegalStateException(
                "Summarization stopped at reduction level "
                        + level + ": " + reason + "."
        );
    }

    private record SummaryPart(
            List<Provenance> provenance,
            String text) {

        private int characterLength() {
            return ReductionBudget.SOURCE_REFERENCE_OVERHEAD + text.length();
        }
    }

    private record Provenance(
            Long documentId,
            Long chunkId,
            int chunkNumber,
            Set<Integer> pageNumbers) {

        private Provenance {
            pageNumbers = pageNumbers == null
                    ? Set.of()
                    : Set.copyOf(pageNumbers);
        }

        private String identity() {
            if (chunkId != null) {
                return "document:" + documentId + ":chunk:" + chunkId;
            }
            return "document:" + documentId + ":chunk-number:" + chunkNumber;
        }
    }
}

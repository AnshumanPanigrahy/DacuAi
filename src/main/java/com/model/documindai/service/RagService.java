package com.model.documindai.service;

import com.model.documindai.model.ContextEvaluationResult;
import com.model.documindai.model.SearchResult;
import com.model.documindai.model.SourceResult;
import com.model.documindai.model.WebSearchResponse;
import com.model.documindai.model.WebSearchResult;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

@Service
public class RagService {

    private static final Logger log =
            LoggerFactory.getLogger(RagService.class);

    private static final int MAX_503_RETRIES = 2;

    private final SearchService searchService;
    private final GeminiModelService geminiModelService;
    private final ContextEvaluator contextEvaluator;
    private final WebSearchService webSearchService;
    private final WebResultEvaluator webResultEvaluator;
    private final WebContextBuilder webContextBuilder;
    private final DocumentSummaryService documentSummaryService;

    public RagService(
            SearchService searchService,
            GeminiModelService geminiModelService,
            ContextEvaluator contextEvaluator,
            WebSearchService webSearchService,
            WebResultEvaluator webResultEvaluator,
            WebContextBuilder webContextBuilder,
            DocumentSummaryService documentSummaryService) {

        this.searchService = searchService;
        this.geminiModelService = geminiModelService;
        this.contextEvaluator = contextEvaluator;
        this.webSearchService = webSearchService;
        this.webResultEvaluator = webResultEvaluator;
        this.webContextBuilder = webContextBuilder;
        this.documentSummaryService = documentSummaryService;
    }

    public void streamAnswer(
            String question,
            Long documentId,
            Consumer<String> onToken,
            Consumer<List<SourceResult>> onSources,
            Consumer<Throwable> onError,
            Runnable onComplete) {
        streamAnswer(
                question,
                documentId,
                onToken,
                onSources,
                onError,
                onComplete,
                () -> false
        );
    }

    public void streamAnswer(
            String question,
            Long documentId,
            Consumer<String> onToken,
            Consumer<List<SourceResult>> onSources,
            Consumer<Throwable> onError,
            Runnable onComplete,
            BooleanSupplier cancelled) {

        if (SummaryIntentDetector.isSummaryRequest(question)) {
            if (documentId == null) {
                onError.accept(new IllegalArgumentException(
                        "A document must be selected before requesting a summary."
                ));
                return;
            }

            documentSummaryService.streamSummary(
                    documentId,
                    onToken,
                    onSources,
                    onError,
                    onComplete,
                    cancelled
            );
            return;
        }

        // --------------------------------------------------
        // 1. Search uploaded PDF documents
        // --------------------------------------------------

        List<SearchResult> results =
                documentId == null
                        ? searchService.search(question)
                        : searchService.searchByDocument(
                                question,
                                documentId
                        );

        // --------------------------------------------------
        // 2. Evaluate PDF context
        // --------------------------------------------------

        ContextEvaluationResult evaluation =
                contextEvaluator.evaluate(
                        question,
                        results
                );

        log.info(
                "Context sufficient: {}",
                evaluation.isSufficient()
        );

        log.info(
                "Context score: {}",
                evaluation.getScore()
        );

        log.info(
                "Context reason: {}",
                evaluation.getReason()
        );

        // --------------------------------------------------
        // 3. Prepare web search variables
        // --------------------------------------------------

        List<WebSearchResult> webResults =
                List.of();

        String webContext = "";

        // --------------------------------------------------
        // 4. If PDF context is insufficient,
        //    search the web
        // --------------------------------------------------

        if (documentId == null && !evaluation.isSufficient()) {

            log.info(
                    "PDF context insufficient. Searching web..."
            );

            try {

                WebSearchResponse webResponse =
                        webSearchService.search(question);

                webResults =
                        webResultEvaluator.filter(
                                webResponse.getResults()
                        );

                webContext =
                        webContextBuilder.build(
                                webResults
                        );

                log.info(
                        "Filtered web results: {}",
                        webResults.size()
                );

            } catch (Exception e) {

                log.error(
                        "Web search failed",
                        e
                );
            }
        }

        // --------------------------------------------------
        // 5. Build PDF context
        // --------------------------------------------------

        StringBuilder context =
                new StringBuilder();

        for (SearchResult result : results) {

            context.append("Document ID: ")
                    .append(result.getDocumentId())
                    .append("\n");

            context.append("Chunk Number: ")
                    .append(result.getChunkNumber())
                    .append("\n");

            context.append("Retrieval Type: ")
                    .append(result.getRetrievalType())
                    .append("\n");

            context.append("Content:\n")
                    .append(result.getText())
                    .append("\n\n");

            context.append("---\n\n");
        }

        log.info(
                "Context characters: {}",
                context.length()
        );

        log.info(
                "PDF chunks sent to Gemini: {}",
                results.size()
        );

        log.info(
                "Web sources sent to Gemini: {}",
                webResults.size()
        );

        // --------------------------------------------------
        // 6. Build source list
        // --------------------------------------------------

        List<SourceResult> sources =
                new ArrayList<>();

        for (SearchResult result : results) {

            sources.add(
                    new SourceResult(
                            result.getDocumentId(),
                            result.getChunkId(),
                            result.getChunkNumber(),
                            result.getPageNumbers()
                    )
            );
        }

        // --------------------------------------------------
        // 7. Send sources to frontend
        // --------------------------------------------------

        onSources.accept(sources);

        // --------------------------------------------------
        // 8. Build Gemini prompt
        // --------------------------------------------------

        String prompt = """
                You are DocuMind AI, an intelligent document
                retrieval and question-answering assistant.

                Answer the user's question using ONLY the
                provided sources.

                %s

                SOURCE RULES:

                1. PDF sources come from the user's uploaded
                   documents.

                2. Web sources come from external web search.

                3. Do not invent information.

                4. Do not use outside knowledge that is not
                   contained in the provided sources.

                5. If PDF and web sources provide complementary
                   information, combine them carefully.

                6. If sources disagree, explicitly mention
                   the disagreement.

                7. Do not assume that two web sources referring
                   to the same name represent the same person
                   or entity.

                8. Search relevance scores are NOT probabilities
                   of truth.

                9. Prefer information that is consistently
                   supported by multiple reliable sources.

                10. If the provided sources do not contain
                    enough information to answer the question,
                    say:

                    "I could not find enough reliable information
                    in the available sources."

                11. Give a clear and concise answer.

                PDF CONTEXT:
                %s

                WEB CONTEXT:
                %s

                USER QUESTION:
                %s

                ANSWER:
                """.formatted(
                documentId == null
                        ? ""
                        : "The user selected one PDF. Use only that PDF; "
                          + "do not use web knowledge or other documents.",
                context,
                webContext,
                question
        );

        // --------------------------------------------------
        // 9. Get all Gemini models
        // --------------------------------------------------

        List<StreamingChatModel> models =
                geminiModelService.getModels();

        log.info(
                "Available Gemini models: {}",
                models.size()
        );

        // --------------------------------------------------
        // 10. Try Gemini models
        // --------------------------------------------------

        streamWithFallback(
                models,
                0,
                0,
                null,
                prompt,
                onToken,
                onError,
                onComplete
        );
    }

    // ======================================================
    // Gemini fallback + retry
    // ======================================================

    private void streamWithFallback(
            List<StreamingChatModel> models,
            int modelIndex,
            int retryAttempt,
            Throwable lastError,
            String prompt,
            Consumer<String> onToken,
            Consumer<Throwable> onError,
            Runnable onComplete) {

        // --------------------------------------------------
        // No more Gemini models
        // --------------------------------------------------

        if (modelIndex >= models.size()) {

            log.error(
                    "All Gemini API key attempts failed. "
                    + "Last failure: {}.",
                    describeError(lastError)
            );

            onError.accept(
                    new RuntimeException(
                            "All Gemini API keys failed. "
                            + "Last failure: "
                            + describeError(lastError),
                            lastError
                    )
            );

            return;
        }

        StreamingChatModel model =
                models.get(modelIndex);

        log.info(
                "Trying Gemini API key attempt {}.",
                modelIndex + 1
        );

        AtomicBoolean responseStarted =
                new AtomicBoolean(false);

        model.chat(
                prompt,
                new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(
                            String partialResponse) {

                        responseStarted.set(true);

                        onToken.accept(
                                partialResponse
                        );
                    }

                    @Override
                    public void onCompleteResponse(
                            ChatResponse completeResponse) {

                        log.info(
                                "Gemini API key attempt {} succeeded.",
                                modelIndex + 1
                        );

                        onComplete.run();
                    }

                    @Override
                    public void onError(
                            Throwable error) {

                        log.error(
                                "Gemini API key attempt {} failed: {}.",
                                modelIndex + 1,
                                describeError(error)
                        );

                        // ------------------------------------------
                        // If Gemini already sent tokens,
                        // do not retry because retrying could
                        // duplicate part of the answer.
                        // ------------------------------------------

                        if (responseStarted.get()) {

                            log.error(
                                    "Gemini failed after response "
                                    + "streaming had already started: {}.",
                                    describeError(error)
                            );

                            onError.accept(error);

                            return;
                        }

                        // ------------------------------------------
                        // 503 = temporary service overload
                        // ------------------------------------------

                        if (is503Error(error)) {

                            if (retryAttempt
                                < MAX_503_RETRIES) {

                                int nextAttempt =
                                        retryAttempt + 1;

                                long delay =
                                        2000L
                                        * (1L
                                           << retryAttempt);

                                log.warn(
                                        "Gemini returned 503. "
                                        + "Retrying key {} "
                                        + "in {} ms "
                                        + "(attempt {}/{})",
                                        modelIndex + 1,
                                        delay,
                                        nextAttempt,
                                        MAX_503_RETRIES
                                );

                                sleep(delay);

                                streamWithFallback(
                                        models,
                                        modelIndex,
                                        nextAttempt,
                                        error,
                                        prompt,
                                        onToken,
                                        onError,
                                        onComplete
                                );

                                return;
                            }

                            log.warn(
                                    "Gemini key {} still unavailable "
                                    + "after {} retries. "
                                    + "Trying next key.",
                                    modelIndex + 1,
                                    MAX_503_RETRIES
                            );

                            streamWithFallback(
                                    models,
                                    modelIndex + 1,
                                    0,
                                    error,
                                    prompt,
                                    onToken,
                                    onError,
                                    onComplete
                            );

                            return;
                        }

                        // ------------------------------------------
                        // 429 = quota / rate limit
                        // ------------------------------------------

                        if (is429Error(error)) {

                            log.warn(
                                    "Gemini key {} reached a "
                                    + "rate/quota limit. "
                                    + "Trying next key.",
                                    modelIndex + 1
                            );

                            streamWithFallback(
                                    models,
                                    modelIndex + 1,
                                    0,
                                    error,
                                    prompt,
                                    onToken,
                                    onError,
                                    onComplete
                            );

                            return;
                        }

                        // ------------------------------------------
                        // 401 / 403 = authentication/permission
                        // ------------------------------------------

                        if (isAuthenticationError(error)) {

                            log.warn(
                                    "Gemini key {} appears to have "
                                    + "an authentication or "
                                    + "permission problem. "
                                    + "Trying next key.",
                                    modelIndex + 1
                            );

                            streamWithFallback(
                                    models,
                                    modelIndex + 1,
                                    0,
                                    error,
                                    prompt,
                                    onToken,
                                    onError,
                                    onComplete
                            );

                            return;
                        }

                        // ------------------------------------------
                        // Other errors
                        // ------------------------------------------

                        log.error(
                                "Non-retryable Gemini error on key "
                                + "attempt {}: {}.",
                                modelIndex + 1,
                                describeError(error)
                        );

                        onError.accept(error);
                    }
                }
        );
    }

    // ======================================================
    // Detect 503
    // ======================================================

    private boolean is503Error(Throwable error) {

        return containsErrorText(
                error,
                "503",
                "UNAVAILABLE",
                "high demand"
        );
    }

    // ======================================================
    // Detect 429
    // ======================================================

    private boolean is429Error(Throwable error) {

        return containsErrorText(
                error,
                "429",
                "RESOURCE_EXHAUSTED",
                "quota",
                "rate limit"
        );
    }

    // ======================================================
    // Detect authentication errors
    // ======================================================

    private boolean isAuthenticationError(Throwable error) {

        return containsErrorText(
                error,
                "401",
                "403",
                "UNAUTHENTICATED",
                "PERMISSION_DENIED"
        );
    }

    private boolean containsErrorText(
            Throwable error,
            String... indicators) {

        for (Throwable cause = error;
             cause != null;
             cause = cause.getCause()) {

            String message = cause.getMessage();

            if (message == null) {
                continue;
            }

            String normalized =
                    message.toLowerCase(Locale.ROOT);

            for (String indicator : indicators) {

                if (normalized.contains(
                        indicator.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }

        return false;
    }

    private String describeError(Throwable error) {

        if (error == null) {
            return "no underlying exception available";
        }

        String exceptionType =
                error.getClass().getSimpleName();

        if (is503Error(error)) {
            return "HTTP 503/service unavailable ("
                   + exceptionType + ")";
        }

        if (is429Error(error)) {
            return "HTTP 429/rate or quota limit ("
                   + exceptionType + ")";
        }

        if (containsErrorText(error, "401", "UNAUTHENTICATED")) {
            return "HTTP 401/authentication failure ("
                   + exceptionType + ")";
        }

        if (containsErrorText(error, "403", "PERMISSION_DENIED")) {
            return "HTTP 403/permission failure ("
                   + exceptionType + ")";
        }

        if (isAuthenticationError(error)) {
            return "authentication/permission failure ("
                   + exceptionType + ")";
        }

        return "unclassified (" + exceptionType + ")";
    }

    private void sleep(long milliseconds) {

        try {

            Thread.sleep(milliseconds);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            log.warn(
                    "Gemini retry interrupted."
            );
        }
    }
}
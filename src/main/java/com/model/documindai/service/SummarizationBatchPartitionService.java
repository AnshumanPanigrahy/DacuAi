package com.model.documindai.service;

import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.entity.SummarizationBatch;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.SummarizationBatchRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class SummarizationBatchPartitionService {

    private final SummarizationJobRepository jobRepository;
    private final SummarizationBatchRepository batchRepository;
    private final DocumentChunkRepository chunkRepository;
    private final int pageSize;
    private final int batchCharacterBudget;

    public SummarizationBatchPartitionService(
            SummarizationJobRepository jobRepository,
            SummarizationBatchRepository batchRepository,
            DocumentChunkRepository chunkRepository,
            @Value("${documind.summarization.worker.chunk-page-size:250}")
            int pageSize,
            @Value("${documind.summarization.worker.batch-chunk-character-budget:10000}")
            int batchCharacterBudget) {
        if (pageSize < 1 || batchCharacterBudget < 1) {
            throw new IllegalArgumentException(
                    "Chunk page size and batch budget must be positive."
            );
        }
        this.jobRepository = jobRepository;
        this.batchRepository = batchRepository;
        this.chunkRepository = chunkRepository;
        this.pageSize = pageSize;
        this.batchCharacterBudget = batchCharacterBudget;
    }

    @Transactional
    public List<SummarizationBatch> createOrLoad(
            UUID jobId,
            long deadlineNanos,
            int effectiveBatchBudget) {
        var lockedJob = jobRepository.findByIdForPartitionUpdate(jobId);
        var job = (lockedJob == null || lockedJob.isEmpty()
                ? jobRepository.findById(jobId)
                : lockedJob)
                .orElseThrow(() -> new IllegalStateException(
                        "Summarization job disappeared."
                ));
        List<SummarizationBatch> existing =
                batchRepository.findByJob_IdOrderByBatchNumberAsc(jobId);
        if (!existing.isEmpty()
                && job.getTotalBatchCount() > 0
                && existing.size() == job.getTotalBatchCount()) {
            validateExistingShape(existing);
            return existing;
        }
        // Lightweight worker tests may provide only the persisted job/batches.
        // Production jobs always have a document and therefore use discovery.
        if (job.getDocument() == null && !existing.isEmpty()) {
            return existing;
        }
        List<Boundary> boundaries = discover(
                job.getDocument().getId(),
                deadlineNanos,
                Math.min(batchCharacterBudget, effectiveBatchBudget)
        );
        validateExisting(existing, boundaries);

        for (Boundary boundary : boundaries) {
            if (existing.stream().noneMatch(batch ->
                    batch.getBatchNumber() == boundary.number())) {
                SummarizationBatch batch = new SummarizationBatch();
                batch.setJob(job);
                batch.setBatchNumber(boundary.number());
                batch.setStatus(com.model.documindai.entity
                        .SummarizationBatchStatus.PENDING);
                batch.setFirstChunkNumber(boundary.first());
                batch.setLastChunkNumber(boundary.last());
                batch.setInputCharacterCount(0);
                batch.setOutputCharacterCount(0);
                batch.setAttemptCount(0);
                batch.setGeminiCallCount(0);
                batch.setLeaseGeneration(job.getLeaseGeneration());
                batchRepository.save(batch);
            }
        }
        return batchRepository.findByJob_IdOrderByBatchNumberAsc(jobId);
    }

    private List<Boundary> discover(
            Long documentId,
            long deadlineNanos,
            int budget) {
        List<Boundary> boundaries = new ArrayList<>();
        int batchNumber = 0;
        int first = -1;
        int last = -1;
        int characters = 0;
        int cursorNumber = -1;
        long cursorId = 0;
        while (true) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new IllegalStateException(
                        "Summarization deadline exceeded while loading chunks."
                );
            }
            List<DocumentChunk> page = chunkRepository.findNextPage(
                    documentId,
                    cursorNumber,
                    cursorId,
                    PageRequest.of(0, pageSize)
            );
            if (page.isEmpty()) {
                break;
            }
            page.sort(Comparator.comparingInt(DocumentChunk::getChunkNumber)
                    .thenComparing(DocumentChunk::getId));
            for (DocumentChunk chunk : page) {
                if (chunk.getChunkNumber() == cursorNumber) {
                    throw new IllegalStateException(
                            "Document chunk numbers must be unique for partitioning."
                    );
                }
                int length = chunk.getChunkText() == null
                        ? 0 : chunk.getChunkText().length();
                if (first < 0) {
                    first = chunk.getChunkNumber();
                }
                if (last >= 0 && characters > 0
                        && characters + length > budget) {
                    boundaries.add(new Boundary(batchNumber++, first, last));
                    first = chunk.getChunkNumber();
                    characters = 0;
                }
                characters += length;
                last = chunk.getChunkNumber();
                cursorNumber = chunk.getChunkNumber();
                cursorId = chunk.getId();
            }
            if (page.size() < pageSize) {
                break;
            }
        }
        if (first >= 0) {
            boundaries.add(new Boundary(batchNumber, first, last));
        }
        return boundaries;
    }

    private void validateExisting(
            List<SummarizationBatch> existing,
            List<Boundary> boundaries) {
        if (existing.stream().map(SummarizationBatch::getBatchNumber).distinct()
                .count() != existing.size()) {
            throw new IllegalStateException("Duplicate persisted batch numbers.");
        }

        for (SummarizationBatch batch : existing) {
            if (batch.getBatchNumber() < 0
                    || batch.getBatchNumber() >= boundaries.size()) {
                throw new IllegalStateException(
                        "Persisted batches do not cover the document."
                );
            }
            Boundary boundary = boundaries.get(batch.getBatchNumber());
            if (batch.getFirstChunkNumber() != boundary.first()
                    || batch.getLastChunkNumber() != boundary.last()) {
                throw new IllegalStateException(
                        "Persisted summarization batch boundaries changed."
                );
            }
        }

        for (int i = 1; i < existing.size(); i++) {
            SummarizationBatch previous = existing.get(i - 1);
            SummarizationBatch current = existing.get(i);
            if (current.getFirstChunkNumber()
                    <= previous.getLastChunkNumber()) {
                throw new IllegalStateException(
                        "Persisted summarization batches overlap."
                );
            }
        }

    }

    private void validateExistingShape(List<SummarizationBatch> existing) {
        for (int i = 0; i < existing.size(); i++) {
            SummarizationBatch batch = existing.get(i);
            if (batch.getBatchNumber() != i
                    || batch.getFirstChunkNumber() > batch.getLastChunkNumber()) {
                throw new IllegalStateException(
                        "Persisted summarization batch partition is invalid."
                );
            }
        }
    }

    private record Boundary(int number, int first, int last) {
    }
}

package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.SummarizationBatch;
import com.model.documindai.entity.SummarizationBatchStatus;
import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.SummarizationBatchRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizationBatchWorkerTest {

    @Mock
    private SummarizationJobClaimService claimService;
    @Mock
    private SummarizationJobRepository jobRepository;
    @Mock
    private SummarizationBatchRepository batchRepository;
    @Mock
    private DocumentChunkRepository chunkRepository;
    @Mock
    private DocumentSummaryService summaryService;
    @Mock
    private ThreadPoolTaskExecutor executor;

    private SummarizationBatchWorker worker;

    @BeforeEach
    void setUp() {
        worker = new SummarizationBatchWorker(
                claimService,
                jobRepository,
                batchRepository,
                chunkRepository,
                summaryService,
                executor,
                1000,
                300000,
                120000,
                9000,
                12000,
                2000
        );
    }

    @Test
    void completedBatchIsNotProcessedAgain() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = activeJob(jobId, false);
        SummarizationBatch batch = batch(job, SummarizationBatchStatus.COMPLETED);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(batchRepository.findByJob_IdOrderByBatchNumberAsc(jobId))
                .thenReturn(List.of(batch));
        when(batchRepository.countByJob_IdAndStatus(
                jobId, SummarizationBatchStatus.COMPLETED
        )).thenReturn(1L);
        when(jobRepository.updateOwnedBatchTotals(
                eq(jobId), eq("worker-a"), eq(0L), any(), any(), anyInt(), anyInt(),
                eq(SummarizationJobStage.SUMMARIZING_BATCHES)
        )).thenReturn(1);
        when(jobRepository.updateOwnedProgress(
                eq(jobId), eq("worker-a"), eq(0L), any(), any(), anyInt(),
                eq(70), eq(SummarizationJobStage.REDUCING_BATCH_SUMMARIES)
        )).thenReturn(1);

        worker.process(jobId, "worker-a");

        verify(summaryService, never()).summarizeBatch(
                any(), anyLong(), any()
        );
    }

    @Test
    void cancellationStopsBeforeProcessingNextBatch() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = activeJob(jobId, true);
        SummarizationBatch batch = batch(job, SummarizationBatchStatus.PENDING);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(batchRepository.findByJob_IdOrderByBatchNumberAsc(jobId))
                .thenReturn(List.of(batch));
        when(batchRepository.countByJob_IdAndStatus(
                jobId, SummarizationBatchStatus.COMPLETED
        )).thenReturn(0L);
        when(jobRepository.updateOwnedBatchTotals(
                eq(jobId), eq("worker-a"), eq(0L), any(), any(), anyInt(), anyInt(),
                eq(SummarizationJobStage.SUMMARIZING_BATCHES)
        )).thenReturn(1);
        when(jobRepository.finishOwnedJob(
                eq(jobId), eq("worker-a"), eq(0L),
                eq(SummarizationJobStatus.CANCELLED),
                anyString(), anyString(), any()
        )).thenReturn(1);

        worker.process(jobId, "worker-a");

        verify(batchRepository, never()).claimPendingBatch(
                any(), anyString(), anyLong(), anyInt()
        );
        verify(summaryService, never()).summarizeBatch(
                any(), anyLong(), any()
        );
    }

    @Test
    void staleGenerationCannotProcessOrPersistWork() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = activeJob(jobId, false);
        job.setLeaseGeneration(2);
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        worker.process(jobId, "worker-a", 1);

        verify(summaryService, never()).summarizeBatch(
                any(), anyLong(), any()
        );
        verify(jobRepository, never()).finishOwnedJob(
                any(), anyString(), anyLong(), any(), anyString(),
                anyString(), any()
        );
    }

    @Test
    void leaseRenewalUsesWorkerAndGenerationFence() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.renewLease(
                eq(jobId),
                eq("worker-a"),
                eq(7L),
                any(),
                any()
        )).thenReturn(1);

        int renewed = worker.renewLease(jobId, "worker-a", 7L);

        org.junit.jupiter.api.Assertions.assertEquals(1, renewed);
        verify(jobRepository).renewLease(
                eq(jobId),
                eq("worker-a"),
                eq(7L),
                any(),
                any()
        );
    }

    @Test
    void rejectedLeaseRenewalIsReportedByConditionalUpdate() {
        UUID jobId = UUID.randomUUID();
        when(jobRepository.renewLease(
                eq(jobId),
                eq("worker-old"),
                eq(3L),
                any(),
                any()
        )).thenReturn(0);

        int renewed = worker.renewLease(jobId, "worker-old", 3L);

        org.junit.jupiter.api.Assertions.assertEquals(0, renewed);
    }

    private SummarizationJob activeJob(UUID id, boolean cancelled) {
        SummarizationJob job = new SummarizationJob();
        job.setId(id);
        Document document = new Document();
        document.setId(7L);
        job.setDocument(document);
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.SUMMARIZING_BATCHES);
        job.setWorkerId("worker-a");
        job.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        job.setCancellationRequested(cancelled);
        job.setMaximumGeminiCalls(100);
        job.setGeminiCallCount(0);
        job.setTotalBatchCount(1);
        return job;
    }

    private SummarizationBatch batch(
            SummarizationJob job,
            SummarizationBatchStatus status) {
        SummarizationBatch batch = new SummarizationBatch();
        batch.setId(UUID.randomUUID());
        batch.setJob(job);
        batch.setBatchNumber(0);
        batch.setStatus(status);
        batch.setFirstChunkNumber(0);
        batch.setLastChunkNumber(0);
        return batch;
    }
}

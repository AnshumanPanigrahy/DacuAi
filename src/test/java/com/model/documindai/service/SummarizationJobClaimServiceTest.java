package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.repository.SummarizationBatchRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummarizationJobClaimServiceTest {

    @Mock
    private SummarizationJobRepository jobRepository;

    @Mock
    private SummarizationBatchRepository batchRepository;

    @Test
    void claimsOnlyOneQueuedJobAndAssignsWorkerLease() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = queuedJob(jobId);
        when(jobRepository.lockNextQueuedJob()).thenReturn(List.of(jobId));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any(SummarizationJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SummarizationJobClaimService service =
                new SummarizationJobClaimService(
                        jobRepository,
                        batchRepository
                );
        LocalDateTime expiry = LocalDateTime.now().plusMinutes(5);

        Optional<UUID> claimed = service.claimNext("worker-a", expiry);

        assertTrue(claimed.isPresent());
        assertEquals(jobId, claimed.get());
        assertEquals(SummarizationJobStatus.PROCESSING, job.getStatus());
        assertEquals(SummarizationJobStage.LOADING_CHUNKS,
                job.getProcessingStage());
        assertEquals("worker-a", job.getWorkerId());
        assertEquals(1L, job.getLeaseGeneration());
        assertEquals(expiry, job.getLeaseExpiresAt());
        verify(jobRepository).lockNextQueuedJob();
        verify(jobRepository).save(job);
    }

    @Test
    void doesNotClaimWhenPostLockStateIsNoLongerQueued() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = queuedJob(jobId);
        job.setStatus(SummarizationJobStatus.PROCESSING);
        when(jobRepository.lockNextQueuedJob()).thenReturn(List.of(jobId));
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        SummarizationJobClaimService service =
                new SummarizationJobClaimService(
                        jobRepository,
                        batchRepository
                );

        assertTrue(service.claimNext(
                "worker-a",
                LocalDateTime.now().plusMinutes(5)
        ).isEmpty());
    }

    @Test
    void recoveryKeepsCompletedWorkAndResetsOnlyAfterGenerationAdvance() {
        UUID jobId = UUID.randomUUID();
        SummarizationJob job = queuedJob(jobId);
        job.setStatus(SummarizationJobStatus.PROCESSING);
        job.setLeaseGeneration(4);
        when(jobRepository.findExpiredProcessingJobs(any()))
                .thenReturn(List.of(job));
        when(jobRepository.recoverExpiredJob(
                eq(jobId),
                eq(SummarizationJobStatus.QUEUED),
                any()
        )).thenReturn(1);

        SummarizationJobClaimService service =
                new SummarizationJobClaimService(
                        jobRepository,
                        batchRepository
                );

        service.recoverExpired(LocalDateTime.now());

        verify(batchRepository).resetProcessingBatches(jobId, 3);
        verify(jobRepository).recoverExpiredJob(
                eq(jobId),
                eq(SummarizationJobStatus.QUEUED),
                any()
        );
    }

    private SummarizationJob queuedJob(UUID id) {
        SummarizationJob job = new SummarizationJob();
        job.setId(id);
        Document document = new Document();
        document.setId(1L);
        job.setDocument(document);
        job.setStatus(SummarizationJobStatus.QUEUED);
        job.setProcessingStage(SummarizationJobStage.QUEUED);
        job.setProgress(0);
        job.setCancellationRequested(false);
        return job;
    }
}

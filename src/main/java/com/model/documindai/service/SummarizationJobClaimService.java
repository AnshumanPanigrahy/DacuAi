package com.model.documindai.service;

import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.repository.SummarizationBatchRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class SummarizationJobClaimService {

    private final SummarizationJobRepository jobRepository;
    private final SummarizationBatchRepository batchRepository;
    private final int maximumBatchAttempts;

    public SummarizationJobClaimService(
            SummarizationJobRepository jobRepository,
            SummarizationBatchRepository batchRepository) {
        this(jobRepository, batchRepository, 3);
    }

    @Autowired
    public SummarizationJobClaimService(
            SummarizationJobRepository jobRepository,
            SummarizationBatchRepository batchRepository,
            @Value("${documind.summarization.worker.maximum-batch-attempts:3}")
            int maximumBatchAttempts) {
        this.jobRepository = jobRepository;
        this.batchRepository = batchRepository;
        if (maximumBatchAttempts < 1) {
            throw new IllegalArgumentException(
                    "Maximum batch attempts must be positive."
            );
        }
        this.maximumBatchAttempts = maximumBatchAttempts;
    }

    /**
     * The row lock is held only until this transaction commits. Gemini is
     * invoked by the worker after this method returns.
     */
    @Transactional
    public Optional<UUID> claimNext(
            String workerId,
            LocalDateTime leaseExpiry) {
        return claimNextWithGeneration(workerId, leaseExpiry)
                .map(ClaimedJob::jobId);
    }

    @Transactional
    public Optional<ClaimedJob> claimNextWithGeneration(
            String workerId,
            LocalDateTime leaseExpiry) {
        Optional<UUID> id = jobRepository.lockNextQueuedJob()
                .stream()
                .findFirst();
        if (id.isEmpty()) {
            return Optional.empty();
        }

        SummarizationJob job = jobRepository.findById(id.get())
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed summarization job no longer exists."
                ));
        if (job.getStatus() != SummarizationJobStatus.QUEUED
                || job.isCancellationRequested()) {
            return Optional.empty();
        }
        if (job.getDeadlineAt() != null
                && !job.getDeadlineAt().isAfter(LocalDateTime.now())) {
            jobRepository.expireQueuedJob(job.getId(), LocalDateTime.now());
            return Optional.empty();
        }
        job.transitionTo(SummarizationJobStatus.PROCESSING);
        job.setProcessingStage(SummarizationJobStage.LOADING_CHUNKS);
        job.setWorkerId(workerId);
        job.setLeaseGeneration(job.getLeaseGeneration() + 1);
        job.setLeaseExpiresAt(leaseExpiry);
        job.setLastHeartbeatAt(LocalDateTime.now());
        jobRepository.save(job);
        return Optional.of(new ClaimedJob(id.get(), job.getLeaseGeneration()));
    }

    @Transactional
    public void recoverExpired(LocalDateTime now) {
        jobRepository.findExpiredProcessingJobs(now)
                .forEach(job -> {
                    if (job.isCancellationRequested()) {
                        jobRepository.cancelExpiredJob(job.getId(), now);
                        return;
                    }
                    int recovered = jobRepository.recoverExpiredJob(
                            job.getId(),
                            SummarizationJobStatus.QUEUED,
                            now
                    );
                    if (recovered == 1) {
                        batchRepository.failExhaustedProcessingBatches(
                                job.getId(), maximumBatchAttempts
                        );
                        batchRepository.resetProcessingBatches(
                                job.getId(), maximumBatchAttempts
                        );
                        if (batchRepository
                                .findByJob_IdAndStatusOrderByBatchNumberAsc(
                                job.getId(),
                                com.model.documindai.entity
                                        .SummarizationBatchStatus.FAILED
                        ).stream().anyMatch(batch ->
                                batch.getStatus()
                                        == com.model.documindai.entity
                                        .SummarizationBatchStatus.FAILED)) {
                            jobRepository.failRecoveredJob(
                                    job.getId(), now
                            );
                        }
                    }
                });
    }

    public record ClaimedJob(UUID jobId, long leaseGeneration) {
    }
}

package com.model.documindai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.entity.SummarizationBatch;
import com.model.documindai.entity.SummarizationBatchStatus;
import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;
import com.model.documindai.model.SourceResult;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.SummarizationBatchRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SummarizationBatchWorker {

    private static final String ERROR_CANCELLED = "SUMMARIZATION_CANCELLED";
    private static final String ERROR_LEASE_LOST = "SUMMARIZATION_LEASE_LOST";
    private static final String ERROR_BATCH_FAILED = "SUMMARIZATION_BATCH_FAILED";
    private static final String ERROR_CALL_LIMIT = "GEMINI_CALL_LIMIT_REACHED";
    private static final String ERROR_NO_CHUNKS = "NO_READABLE_CHUNKS";
    private static final String ERROR_AUTHENTICATION =
            "GEMINI_AUTHENTICATION_FAILED";

    private final SummarizationJobClaimService claimService;
    private final SummarizationJobRepository jobRepository;
    private final SummarizationBatchRepository batchRepository;
    private final DocumentChunkRepository chunkRepository;
    private final DocumentSummaryService summaryService;
    private final SummarizationBatchPartitionService partitionService;
    private final ThreadPoolTaskExecutor executor;
    private final ScheduledThreadPoolExecutor heartbeatExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long pollDelayMillis;
    private final long leaseMillis;
    private final long maximumDurationMillis;
    private final int batchCharacterBudget;
    private final int inputCharacterBudget;
    private final int outputCharacterAllowance;
    private final int maximumBatchAttempts;

    public SummarizationBatchWorker(
            SummarizationJobClaimService claimService,
            SummarizationJobRepository jobRepository,
            SummarizationBatchRepository batchRepository,
            DocumentChunkRepository chunkRepository,
            DocumentSummaryService summaryService,
            ThreadPoolTaskExecutor executor,
            long pollDelayMillis,
            long leaseMillis,
            long maximumDurationMillis,
            int batchCharacterBudget,
            int inputCharacterBudget,
            int outputCharacterAllowance) {
        this(
                claimService,
                jobRepository,
                batchRepository,
                chunkRepository,
                summaryService,
                new SummarizationBatchPartitionService(
                        jobRepository,
                        batchRepository,
                        chunkRepository,
                        250,
                        batchCharacterBudget
                ),
                executor,
                new ScheduledThreadPoolExecutor(1),
                pollDelayMillis,
                leaseMillis,
                maximumDurationMillis,
                batchCharacterBudget,
                inputCharacterBudget,
                outputCharacterAllowance
                , 3
        );
    }

    @Autowired
    public SummarizationBatchWorker(
            SummarizationJobClaimService claimService,
            SummarizationJobRepository jobRepository,
            SummarizationBatchRepository batchRepository,
            DocumentChunkRepository chunkRepository,
            DocumentSummaryService summaryService,
            SummarizationBatchPartitionService partitionService,
            @Qualifier("summarizationTaskExecutor")
            ThreadPoolTaskExecutor executor,
            @Qualifier("summarizationHeartbeatExecutor")
            ScheduledThreadPoolExecutor heartbeatExecutor,
            @Value("${documind.summarization.worker.poll-delay-millis:1000}")
            long pollDelayMillis,
            @Value("${documind.summarization.worker.lease-millis:300000}")
            long leaseMillis,
            @Value("${documind.summarization.maximum-duration-millis:120000}")
            long maximumDurationMillis,
            @Value("${documind.summarization.worker.batch-chunk-character-budget:10000}")
            int batchCharacterBudget,
            @Value("${documind.summarization.input-character-budget:12000}")
            int inputCharacterBudget,
            @Value("${documind.summarization.output-character-allowance:2000}")
            int outputCharacterAllowance,
            @Value("${documind.summarization.worker.maximum-batch-attempts:3}")
            int maximumBatchAttempts) {
        if (pollDelayMillis < 100
                || leaseMillis < 1
                || maximumDurationMillis < 1
                || batchCharacterBudget < 1
                || inputCharacterBudget <= outputCharacterAllowance) {
            throw new IllegalArgumentException(
                    "Invalid summarization worker settings."
            );
        }
        this.claimService = claimService;
        this.jobRepository = jobRepository;
        this.batchRepository = batchRepository;
        this.chunkRepository = chunkRepository;
        this.summaryService = summaryService;
        this.partitionService = partitionService;
        this.executor = executor;
        this.heartbeatExecutor = heartbeatExecutor;
        this.pollDelayMillis = pollDelayMillis;
        this.leaseMillis = leaseMillis;
        this.maximumDurationMillis = maximumDurationMillis;
        this.batchCharacterBudget = Math.min(
                batchCharacterBudget,
                inputCharacterBudget - outputCharacterAllowance - 512
        );
        this.inputCharacterBudget = inputCharacterBudget;
        this.outputCharacterAllowance = outputCharacterAllowance;
        if (maximumBatchAttempts < 1) {
            throw new IllegalArgumentException(
                    "Maximum batch attempts must be positive."
            );
        }
        this.maximumBatchAttempts = maximumBatchAttempts;
    }

    @Scheduled(fixedDelayString =
            "${documind.summarization.worker.poll-delay-millis:1000}")
    public void poll() {
        claimService.recoverExpired(LocalDateTime.now());
        if (!hasExecutorCapacity()) {
            return;
        }

        String workerId = "worker-" + UUID.randomUUID();
        Optional<SummarizationJobClaimService.ClaimedJob> claimed =
                claimService.claimNextWithGeneration(
                workerId,
                LocalDateTime.now().plusNanos(
                        TimeUnit.MILLISECONDS.toNanos(leaseMillis)
                )
        );
        if (claimed.isEmpty()) {
            return;
        }

        try {
            SummarizationJobClaimService.ClaimedJob claim = claimed.get();
            executor.execute(() -> process(
                    claim.jobId(),
                    workerId,
                    claim.leaseGeneration()
            ));
        } catch (RejectedExecutionException rejected) {
            SummarizationJobClaimService.ClaimedJob claim = claimed.get();
            jobRepository.releaseOwnedJob(
                    claim.jobId(),
                    workerId,
                    claim.leaseGeneration()
            );
        }
    }

    public void process(UUID jobId, String workerId) {
        long generation = jobRepository.findById(jobId)
                .map(com.model.documindai.entity.SummarizationJob
                        ::getLeaseGeneration)
                .orElse(-1L);
        process(jobId, workerId, generation);
    }

    public void process(
            UUID jobId,
            String workerId,
            long leaseGeneration) {
        AtomicBoolean leaseLost = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = heartbeatExecutor
                .scheduleAtFixedRate(
                        () -> {
                            try {
                                if (renewLease(
                                        jobId, workerId, leaseGeneration
                                ) != 1) {
                                    leaseLost.set(true);
                                }
                            } catch (RuntimeException error) {
                                leaseLost.set(true);
                            }
                        },
                        heartbeatPeriodMillis(),
                        heartbeatPeriodMillis(),
                        TimeUnit.MILLISECONDS
                );
        try {
            processClaimedJob(
                    jobId, workerId, leaseGeneration, leaseLost
            );
        } catch (Throwable error) {
            if (!leaseLost.get()) {
                failOwnedJob(
                        jobId,
                        workerId,
                        leaseGeneration,
                        ERROR_BATCH_FAILED
                );
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void processClaimedJob(
            UUID jobId,
            String workerId,
            long leaseGeneration,
            AtomicBoolean leaseLost) {
        var job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Summarization job disappeared."
                ));
        if (!ownedProcessingJob(job, workerId, leaseGeneration)) {
            return;
        }

        LocalDateTime deadlineAt = effectiveDeadline(job);
        if (deadlineAt.isAfter(LocalDateTime.now())) {
            // Convert the persisted wall-clock deadline to a monotonic
            // deadline once for this execution.
        } else {
            expireOwnedJob(jobId, workerId, leaseGeneration);
            return;
        }
        long deadline = System.nanoTime() + Math.max(
                0,
                TimeUnit.NANOSECONDS.convert(
                        java.time.Duration.between(
                                LocalDateTime.now(), deadlineAt
                        ).toMillis(),
                        TimeUnit.MILLISECONDS
                )
        );
        if (leaseLost.get()) {
            return;
        }
        List<SummarizationBatch> persisted =
                partitionService.createOrLoad(
                        jobId, deadline, effectiveBatchBudget()
                );
        List<BatchDefinition> definitions = persisted.stream()
                .map(batch -> new BatchDefinition(
                        batch.getBatchNumber(),
                        batch.getFirstChunkNumber(),
                        batch.getLastChunkNumber()
                ))
                .toList();
        if (definitions.isEmpty()) {
            failOwnedJob(jobId, workerId, leaseGeneration, ERROR_NO_CHUNKS);
            return;
        }
        int completed = (int) batchRepository.countByJob_IdAndStatus(
                jobId,
                SummarizationBatchStatus.COMPLETED
        );
        int total = definitions.size();
        if (jobRepository.updateOwnedBatchTotals(
                jobId,
                workerId,
                leaseGeneration,
                LocalDateTime.now(),
                leaseExpiry(),
                total,
                completed,
                SummarizationJobStage.SUMMARIZING_BATCHES
        ) != 1) {
            failOwnedJob(jobId, workerId, leaseGeneration, ERROR_LEASE_LOST);
            return;
        }

        for (SummarizationBatch batch : persisted) {
            if (batch.getStatus() == SummarizationBatchStatus.COMPLETED) {
                continue;
            }
            if (leaseLost.get()
                    || !isOwnedAndActive(jobId, workerId, leaseGeneration)) {
                return;
            }
            if (isCancellationRequested(jobId)) {
                cancelOwnedJob(jobId, workerId, leaseGeneration);
                return;
            }
            if (System.nanoTime() >= deadline) {
                expireOwnedJob(jobId, workerId, leaseGeneration);
                return;
            }

            if (batchRepository.claimPendingBatch(
                    batch.getId(), workerId, leaseGeneration,
                    maximumBatchAttempts) != 1) {
                continue;
            }
            processBatch(
                    jobId, workerId, leaseGeneration, batch, deadline,
                    leaseLost
            );
        }

        if (leaseLost.get()
                || !isOwnedAndActive(jobId, workerId, leaseGeneration)) {
            return;
        }
        int completedAfter = (int) batchRepository.countByJob_IdAndStatus(
                jobId,
                SummarizationBatchStatus.COMPLETED
        );
        if (completedAfter == total) {
            // This phase deliberately stops before claiming completion: the
            // existing hierarchical reduction/finalization path is not yet a
            // resumable persisted operation.
            if (jobRepository.updateOwnedProgress(
                    jobId,
                    workerId,
                    leaseGeneration,
                    LocalDateTime.now(),
                    leaseExpiry(),
                    completedAfter,
                    70,
                    SummarizationJobStage.REDUCING_BATCH_SUMMARIES
            ) != 1) {
                failOwnedJob(jobId, workerId, leaseGeneration, ERROR_LEASE_LOST);
            }
        }
    }

    private void processBatch(
            UUID jobId,
            String workerId,
            long leaseGeneration,
            SummarizationBatch batch,
            long deadline,
            AtomicBoolean leaseLost) {
        if (leaseLost.get()) {
            return;
        }
        if (isCancellationRequested(jobId)) {
            cancelOwnedJob(jobId, workerId, leaseGeneration);
            return;
        }
        var job = jobRepository.findById(jobId).orElseThrow();

        List<DocumentChunk> chunks = chunkRepository
                .findByDocumentIdAndChunkNumberBetween(
                        job.getDocument().getId(),
                        batch.getFirstChunkNumber(),
                        batch.getLastChunkNumber()
                )
                .stream()
                .sorted(Comparator.comparingInt(DocumentChunk::getChunkNumber))
                .toList();
        if (chunks.isEmpty()) {
            batchRepository.failBatch(
                    batch.getId(), workerId, leaseGeneration, ERROR_NO_CHUNKS
            );
            failOwnedJob(jobId, workerId, leaseGeneration, ERROR_NO_CHUNKS);
            return;
        }
        if (isCancellationRequested(jobId)) {
            cancelOwnedJob(jobId, workerId, leaseGeneration);
            return;
        }

        try {
            AtomicInteger reservedCalls = new AtomicInteger();
            DocumentSummaryService.BatchSummary result =
                    summaryService.summarizeBatch(
                            chunks,
                            deadline,
                            () -> leaseLost.get()
                                    || isCancellationRequested(jobId),
                            () -> {
                                if (leaseLost.get()
                                            || !isOwnedAndActive(
                                            jobId, workerId, leaseGeneration)) {
                                    throw new IllegalStateException(
                                                "Summarization lease was lost."
                                    );
                                }
                                if (System.nanoTime() >= deadline) {
                                    throw new IllegalStateException(
                                            "Summarization deadline exceeded."
                                    );
                                }
                                if (jobRepository.reserveGeminiCall(
                                        jobId,
                                        workerId,
                                        leaseGeneration,
                                        LocalDateTime.now()
                                ) != 1) {
                                    throw new IllegalStateException(
                                            "Maximum Gemini call count reached."
                                    );
                                }
                                reservedCalls.incrementAndGet();
                            }
                    );
            if (leaseLost.get()) {
                return;
            }
            if (isCancellationRequested(jobId)) {
                cancelOwnedJob(jobId, workerId, leaseGeneration);
                return;
            }
            String metadata = serializeSources(result.sources());
            if (batchRepository.completeBatch(
                    batch.getId(),
                    workerId,
                    leaseGeneration,
                    SummarizationBatchStatus.COMPLETED.name(),
                    result.summary(),
                    metadata,
                    result.inputCharacterCount(),
                    result.outputCharacterCount(),
                    reservedCalls.get(),
                    null,
                    LocalDateTime.now()
            ) != 1) {
                if (isCancellationRequested(jobId)) {
                    cancelOwnedJob(jobId, workerId, leaseGeneration);
                } else {
                    failOwnedJob(jobId, workerId, leaseGeneration,
                            ERROR_LEASE_LOST);
                }
                return;
            }
            updateAfterBatch(
                    jobId, workerId, leaseGeneration,
                    reservedCalls.get()
            );
        } catch (Throwable error) {
            if (leaseLost.get()) {
                return;
            }
            if (isCancellationRequested(jobId)) {
                cancelOwnedJob(jobId, workerId, leaseGeneration);
                return;
            }
            String errorCode = stableErrorCode(error);
            if (!ERROR_AUTHENTICATION.equals(errorCode)
                    && batchRepository.retryBatch(
                    batch.getId(),
                    workerId,
                    leaseGeneration,
                    maximumBatchAttempts,
                    errorCode
            ) == 1) {
                return;
            }
            batchRepository.failBatch(
                    batch.getId(), workerId, leaseGeneration, errorCode
            );
            failOwnedJob(jobId, workerId, leaseGeneration, errorCode);
        }
    }

    private void updateAfterBatch(
            UUID jobId,
            String workerId,
            long leaseGeneration,
            int calls) {
        int completed = (int) batchRepository.countByJob_IdAndStatus(
                jobId,
                SummarizationBatchStatus.COMPLETED
        );
        var job = jobRepository.findById(jobId).orElseThrow();
        int progress = job.getTotalBatchCount() == 0
                ? 0
                : Math.min(
                        70,
                        (int) Math.floor(
                                completed * 70.0 / job.getTotalBatchCount()
                        )
                );
        if (jobRepository.updateOwnedProgress(
                jobId,
                workerId,
                leaseGeneration,
                LocalDateTime.now(),
                leaseExpiry(),
                completed,
                progress,
                SummarizationJobStage.SUMMARIZING_BATCHES
        ) != 1) {
            if (isCancellationRequested(jobId)) {
                cancelOwnedJob(jobId, workerId, leaseGeneration);
            } else {
                failOwnedJob(jobId, workerId, leaseGeneration,
                        ERROR_LEASE_LOST);
            }
        }
    }

    private boolean hasExecutorCapacity() {
        var pool = executor.getThreadPoolExecutor();
        return pool.getActiveCount() < pool.getMaximumPoolSize()
                || pool.getQueue().remainingCapacity() > 0;
    }

    int renewLease(
            UUID jobId,
            String workerId,
            long leaseGeneration) {
        LocalDateTime now = LocalDateTime.now();
        return jobRepository.renewLease(
                jobId,
                workerId,
                leaseGeneration,
                now,
                now.plusNanos(
                        TimeUnit.MILLISECONDS.toNanos(leaseMillis)
                )
        );
    }

    private long heartbeatPeriodMillis() {
        return Math.max(100L, leaseMillis / 3);
    }

    private boolean isOwnedAndActive(
            UUID jobId,
            String workerId,
            long leaseGeneration) {
        return jobRepository.findById(jobId)
                .map(job -> ownedProcessingJob(
                        job, workerId, leaseGeneration
                ))
                .orElse(false);
    }

    private boolean ownedProcessingJob(
            com.model.documindai.entity.SummarizationJob job,
            String workerId,
            long leaseGeneration) {
        return job.getStatus() == SummarizationJobStatus.PROCESSING
                && workerId.equals(job.getWorkerId())
                && job.getLeaseGeneration() == leaseGeneration
                && job.getLeaseExpiresAt() != null
                && job.getLeaseExpiresAt().isAfter(LocalDateTime.now());
    }

    private boolean isCancellationRequested(UUID jobId) {
        return jobRepository.findById(jobId)
                .map(com.model.documindai.entity.SummarizationJob::isCancellationRequested)
                .orElse(true);
    }

    private void cancelOwnedJob(
            UUID jobId,
            String workerId,
            long leaseGeneration) {
        jobRepository.finishOwnedJob(
                jobId,
                workerId,
                leaseGeneration,
                SummarizationJobStatus.CANCELLED,
                ERROR_CANCELLED,
                "Summarization was cancelled.",
                LocalDateTime.now()
        );
    }

    private void failOwnedJob(
            UUID jobId,
            String workerId,
            long leaseGeneration,
            String errorCode) {
        jobRepository.finishOwnedJob(
                jobId,
                workerId,
                leaseGeneration,
                SummarizationJobStatus.FAILED,
                errorCode,
                "Summarization could not be completed.",
                LocalDateTime.now()
        );
    }

    private void expireOwnedJob(
            UUID jobId,
            String workerId,
            long leaseGeneration) {
        jobRepository.expireOwnedJob(
                jobId, workerId, leaseGeneration, LocalDateTime.now()
        );
    }

    private LocalDateTime effectiveDeadline(
            com.model.documindai.entity.SummarizationJob job) {
        if (job.getDeadlineAt() != null) {
            return job.getDeadlineAt();
        }
        LocalDateTime created = job.getCreatedAt() == null
                ? LocalDateTime.now()
                : job.getCreatedAt();
        return created.plusNanos(
                TimeUnit.MILLISECONDS.toNanos(maximumDurationMillis)
        );
    }

    private String serializeSources(List<SourceResult> sources) {
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Unable to persist batch source metadata.", error
            );
        }
    }

    private String stableErrorCode(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message != null && message.contains("maximum Gemini call")) {
            return ERROR_CALL_LIMIT;
        }
        if (message != null && message.contains("deadline")) {
            return "SUMMARIZATION_DEADLINE_EXCEEDED";
        }
        if (message != null && (message.contains("authentication")
                || message.contains("rejected"))) {
            return ERROR_AUTHENTICATION;
        }
        return ERROR_BATCH_FAILED;
    }

    private LocalDateTime leaseExpiry() {
        return LocalDateTime.now().plusNanos(
                TimeUnit.MILLISECONDS.toNanos(leaseMillis)
        );
    }

    private int effectiveBatchBudget() {
        return Math.max(
                1,
                Math.min(
                        batchCharacterBudget,
                        inputCharacterBudget
                                - outputCharacterAllowance
                                - 512
                )
        );
    }

    private record BatchDefinition(
            int batchNumber,
            int firstChunkNumber,
            int lastChunkNumber) {
    }
}

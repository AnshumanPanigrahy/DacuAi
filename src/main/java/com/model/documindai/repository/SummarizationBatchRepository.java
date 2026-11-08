package com.model.documindai.repository;

import com.model.documindai.entity.SummarizationBatch;
import com.model.documindai.entity.SummarizationBatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SummarizationBatchRepository
        extends JpaRepository<SummarizationBatch, UUID> {

    List<SummarizationBatch> findByJob_IdOrderByBatchNumberAsc(UUID jobId);

    Optional<SummarizationBatch> findByJob_IdAndBatchNumber(
            UUID jobId,
            int batchNumber
    );

    List<SummarizationBatch> findByJob_IdAndStatusOrderByBatchNumberAsc(
            UUID jobId,
            SummarizationBatchStatus status
    );

    long countByJob_IdAndStatus(UUID jobId, SummarizationBatchStatus status);

    @Modifying
    @Transactional
    @Query("""
            update SummarizationBatch batch
               set batch.status =
                   com.model.documindai.entity.SummarizationBatchStatus.PENDING,
                   batch.workerId = null,
                   batch.errorCode = :errorCode
             where batch.id = :batchId
               and batch.status =
                   com.model.documindai.entity.SummarizationBatchStatus.PROCESSING
               and batch.attemptCount < :maximumAttempts
               and batch.workerId = :workerId
               and batch.leaseGeneration = :leaseGeneration
            """)
    int retryBatch(
            @Param("batchId") UUID batchId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("maximumAttempts") int maximumAttempts,
            @Param("errorCode") String errorCode
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationBatch batch
               set batch.status = com.model.documindai.entity.SummarizationBatchStatus.PENDING,
                   batch.workerId = null
             where batch.job.id = :jobId
               and batch.job.status =
                   com.model.documindai.entity.SummarizationJobStatus.QUEUED
               and batch.leaseGeneration < batch.job.leaseGeneration
               and batch.status =
                   com.model.documindai.entity.SummarizationBatchStatus.PROCESSING
               and batch.attemptCount < :maximumAttempts
            """)
    int resetProcessingBatches(
            @Param("jobId") UUID jobId,
            @Param("maximumAttempts") int maximumAttempts
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationBatch batch
               set batch.status =
                   com.model.documindai.entity.SummarizationBatchStatus.FAILED,
                   batch.errorCode = 'SUMMARIZATION_RETRY_EXHAUSTED',
                   batch.workerId = null
             where batch.job.id = :jobId
               and batch.job.status =
                   com.model.documindai.entity.SummarizationJobStatus.QUEUED
               and batch.job.cancellationRequested = false
               and batch.leaseGeneration < batch.job.leaseGeneration
               and batch.status =
                   com.model.documindai.entity.SummarizationBatchStatus.PROCESSING
               and batch.attemptCount >= :maximumAttempts
            """)
    int failExhaustedProcessingBatches(
            @Param("jobId") UUID jobId,
            @Param("maximumAttempts") int maximumAttempts
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationBatch batch
               set batch.status = com.model.documindai.entity.SummarizationBatchStatus.PROCESSING,
                   batch.workerId = :workerId,
               batch.leaseGeneration = :leaseGeneration,
               batch.attemptCount = batch.attemptCount + 1
             where batch.id = :batchId
               and batch.status = com.model.documindai.entity.SummarizationBatchStatus.PENDING
               and batch.attemptCount < :maximumAttempts
               and batch.job.status =
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and batch.job.workerId = :workerId
               and batch.job.leaseGeneration = :leaseGeneration
               and batch.job.leaseExpiresAt > CURRENT_TIMESTAMP
               and batch.job.cancellationRequested = false
            """)
    int claimPendingBatch(
            @Param("batchId") UUID batchId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("maximumAttempts") int maximumAttempts
    );

    @Modifying
    @Transactional
    @Query(value = """
            WITH locked_job AS (
                SELECT id
                  FROM summarization_jobs
                 WHERE id = (
                     SELECT job_id FROM summarization_batches
                      WHERE id = :batchId
                 )
                 FOR UPDATE
            )
            UPDATE summarization_batches batch
               SET status = CAST(:status AS varchar),
                   summary_text = :summaryText,
                   source_metadata = :sourceMetadata,
                   input_character_count = :inputCharacterCount,
                   output_character_count = :outputCharacterCount,
                   gemini_call_count = :geminiCallCount,
                   error_code = :errorCode,
                   completed_at = :completedAt
              FROM locked_job job
             WHERE batch.id = :batchId
               AND batch.job_id = job.id
               AND batch.status = 'PROCESSING'
               AND batch.worker_id = :workerId
               AND batch.lease_generation = :leaseGeneration
               AND EXISTS (
                   SELECT 1
                     FROM summarization_jobs current_job
                    WHERE current_job.id = job.id
                      AND current_job.status = 'PROCESSING'
                      AND current_job.worker_id = :workerId
                      AND current_job.lease_generation = :leaseGeneration
                      AND current_job.lease_expires_at > CURRENT_TIMESTAMP
                      AND current_job.cancellation_requested = false
               )
            """, nativeQuery = true)
    int completeBatch(
            @Param("batchId") UUID batchId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("status") String status,
            @Param("summaryText") String summaryText,
            @Param("sourceMetadata") String sourceMetadata,
            @Param("inputCharacterCount") int inputCharacterCount,
            @Param("outputCharacterCount") int outputCharacterCount,
            @Param("geminiCallCount") int geminiCallCount,
            @Param("errorCode") String errorCode,
            @Param("completedAt") java.time.LocalDateTime completedAt
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationBatch batch
               set batch.status = com.model.documindai.entity.SummarizationBatchStatus.FAILED,
                   batch.errorCode = :errorCode
             where batch.id = :batchId
               and batch.status = com.model.documindai.entity.SummarizationBatchStatus.PROCESSING
               and batch.workerId = :workerId
               and batch.leaseGeneration = :leaseGeneration
               and batch.job.status =
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and batch.job.workerId = :workerId
               and batch.job.leaseGeneration = :leaseGeneration
               and batch.job.leaseExpiresAt > CURRENT_TIMESTAMP
               and batch.job.cancellationRequested = false
            """)
    int failBatch(
            @Param("batchId") UUID batchId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("errorCode") String errorCode
    );
}

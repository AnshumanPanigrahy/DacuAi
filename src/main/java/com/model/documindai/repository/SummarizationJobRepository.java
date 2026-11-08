package com.model.documindai.repository;

import com.model.documindai.entity.SummarizationJob;
import com.model.documindai.entity.SummarizationJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SummarizationJobRepository
        extends JpaRepository<SummarizationJob, UUID> {

    List<SummarizationJob> findByDocument_Id(Long documentId);

    List<SummarizationJob> findByStatus(SummarizationJobStatus status);

    List<SummarizationJob> findByStatusAndLeaseExpiresAtBefore(
            SummarizationJobStatus status,
            LocalDateTime timestamp
    );

    long countByStatusIn(Collection<SummarizationJobStatus> statuses);

    @Query(value = """
            SELECT id
            FROM summarization_jobs
            WHERE status = 'QUEUED'
              AND cancellation_requested = false
            ORDER BY created_at, id
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    List<UUID> lockNextQueuedJob();

    @Query("""
            select job
              from SummarizationJob job
             where job.id = :jobId
            """)
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<SummarizationJob> findByIdForPartitionUpdate(
            @Param("jobId") UUID jobId
    );

    @Query("""
            select job
              from SummarizationJob job
             where job.id = :jobId
            """)
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<SummarizationJob> findByIdForUpdate(
            @Param("jobId") UUID jobId
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status = :status,
                   job.processingStage =
                       com.model.documindai.entity.SummarizationJobStage.QUEUED,
                   job.workerId = null,
                   job.leaseGeneration = job.leaseGeneration + 1,
                   job.leaseExpiresAt = null,
                   job.lastHeartbeatAt = :now,
                   job.updatedAt = :now
             where job.id = :jobId
               and job.status = com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.leaseExpiresAt < :now
               and job.cancellationRequested = false
            """)
    int recoverExpiredJob(
            @Param("jobId") UUID jobId,
            @Param("status") SummarizationJobStatus status,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status =
                       com.model.documindai.entity.SummarizationJobStatus.CANCELLED,
                   job.errorCode = 'SUMMARIZATION_CANCELLED',
                   job.safeErrorMessage =
                       'Summarization was cancelled.',
                   job.completedAt = :now,
                   job.updatedAt = :now
             where job.id = :jobId
               and job.status =
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.cancellationRequested = true
               and job.leaseExpiresAt < :now
            """)
    int cancelExpiredJob(
            @Param("jobId") UUID jobId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status =
                       com.model.documindai.entity.SummarizationJobStatus.FAILED,
                   job.processingStage =
                       com.model.documindai.entity.SummarizationJobStage.FINALIZING,
                   job.errorCode = 'SUMMARIZATION_DEADLINE_EXCEEDED',
                   job.safeErrorMessage =
                       'Summarization could not be completed.',
                   job.completedAt = :now,
                   job.updatedAt = :now
             where job.id = :jobId
               and job.status =
                   com.model.documindai.entity.SummarizationJobStatus.QUEUED
               and job.cancellationRequested = false
               and job.deadlineAt <= :now
            """)
    int expireQueuedJob(
            @Param("jobId") UUID jobId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.lastHeartbeatAt = :now,
                   job.leaseExpiresAt = :leaseExpiresAt,
                   job.completedBatchCount = :completedBatchCount,
                   job.progress = :progress,
                   job.processingStage = :stage
             where job.id = :jobId
               and job.status = com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.leaseExpiresAt > :now
               and job.cancellationRequested = false
            """)
    int updateOwnedProgress(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("completedBatchCount") int completedBatchCount,
            @Param("progress") int progress,
            @Param("stage") com.model.documindai.entity.SummarizationJobStage stage
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.totalBatchCount = :totalBatchCount,
                   job.completedBatchCount = :completedBatchCount,
                   job.processingStage = :stage,
                   job.lastHeartbeatAt = :now,
                   job.leaseExpiresAt = :leaseExpiresAt
             where job.id = :jobId
               and job.status = com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.leaseExpiresAt > :now
               and job.cancellationRequested = false
            """)
    int updateOwnedBatchTotals(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("totalBatchCount") int totalBatchCount,
            @Param("completedBatchCount") int completedBatchCount,
            @Param("stage") com.model.documindai.entity.SummarizationJobStage stage
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status = :status,
                   job.errorCode = :errorCode,
                   job.safeErrorMessage = :safeErrorMessage,
                   job.completedAt = :completedAt,
                   job.lastHeartbeatAt = :completedAt
             where job.id = :jobId
               and job.status = com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.leaseExpiresAt > CURRENT_TIMESTAMP
               and (
                   :status =
                       com.model.documindai.entity.SummarizationJobStatus.CANCELLED
                   or job.cancellationRequested = false
               )
            """)
    int finishOwnedJob(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("status") SummarizationJobStatus status,
            @Param("errorCode") String errorCode,
            @Param("safeErrorMessage") String safeErrorMessage,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status = com.model.documindai.entity.SummarizationJobStatus.QUEUED,
                   job.processingStage =
                       com.model.documindai.entity.SummarizationJobStage.QUEUED,
                   job.workerId = null,
                   job.leaseExpiresAt = null
             where job.id = :jobId
               and job.status = com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.cancellationRequested = false
            """)
    int releaseOwnedJob(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status =
                       com.model.documindai.entity.SummarizationJobStatus.FAILED,
                   job.processingStage =
                       com.model.documindai.entity.SummarizationJobStage.FINALIZING,
                   job.errorCode = 'SUMMARIZATION_RETRY_EXHAUSTED',
                   job.safeErrorMessage =
                       'Summarization could not be completed.',
                   job.completedAt = :now,
                   job.updatedAt = :now
             where job.id = :jobId
               and job.status =
                   com.model.documindai.entity.SummarizationJobStatus.QUEUED
               and job.cancellationRequested = false
            """)
    int failRecoveredJob(
            @Param("jobId") UUID jobId,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.lastHeartbeatAt = :now,
                   job.leaseExpiresAt = :leaseExpiresAt
             where job.id = :jobId
               and job.status =
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.leaseExpiresAt > :now
            """)
    int renewLease(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("now") LocalDateTime now,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.geminiCallCount = job.geminiCallCount + 1
             where job.id = :jobId
               and job.status =
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.leaseExpiresAt > :now
               and job.cancellationRequested = false
               and job.geminiCallCount < job.maximumGeminiCalls
            """)
    int reserveGeminiCall(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("now") LocalDateTime now
    );

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.cancellationRequested = true,
                   job.status =
                       com.model.documindai.entity.SummarizationJobStatus.CANCELLED,
                   job.processingStage =
                       com.model.documindai.entity.SummarizationJobStage.FINALIZING,
                   job.errorCode = 'SUMMARIZATION_CANCELLED',
                   job.safeErrorMessage = 'Summarization was cancelled.',
                   job.completedAt = CURRENT_TIMESTAMP
             where job.id = :jobId
               and job.status in (
                   com.model.documindai.entity.SummarizationJobStatus.QUEUED,
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               )
            """)
    int requestCancellation(@Param("jobId") UUID jobId);

    @Modifying
    @Transactional
    @Query("""
            update SummarizationJob job
               set job.status =
                       com.model.documindai.entity.SummarizationJobStatus.FAILED,
                   job.processingStage =
                       com.model.documindai.entity.SummarizationJobStage.FINALIZING,
                   job.errorCode = 'SUMMARIZATION_DEADLINE_EXCEEDED',
                   job.safeErrorMessage =
                       'Summarization could not be completed.',
                   job.completedAt = :now,
                   job.updatedAt = :now
             where job.id = :jobId
               and job.status =
                   com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.workerId = :workerId
               and job.leaseGeneration = :leaseGeneration
               and job.deadlineAt <= :now
            """)
    int expireOwnedJob(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("leaseGeneration") long leaseGeneration,
            @Param("now") LocalDateTime now
    );

    @Query("""
            select job
              from SummarizationJob job
             where job.status = com.model.documindai.entity.SummarizationJobStatus.PROCESSING
               and job.leaseExpiresAt < :now
            """)
    List<SummarizationJob> findExpiredProcessingJobs(
            @Param("now") LocalDateTime now
    );
}

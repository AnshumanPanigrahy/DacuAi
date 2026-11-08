package com.model.documindai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "summarization_jobs")
public class SummarizationJob {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SummarizationJobStatus status;

    @Column(nullable = false)
    private int progress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private SummarizationJobStage processingStage;

    @Column(nullable = false)
    private int completedBatchCount;

    @Column(nullable = false)
    private int totalBatchCount;

    @Column(nullable = false)
    private int geminiCallCount;

    @Column(nullable = false)
    private int maximumGeminiCalls;

    @Column(nullable = false)
    private int maximumReductionDepth;

    @Column(nullable = false)
    private boolean cancellationRequested;

    @Column(length = 100)
    private String workerId;

    @Column(nullable = false)
    private long leaseGeneration;

    private LocalDateTime leaseExpiresAt;

    private LocalDateTime lastHeartbeatAt;

    /**
     * Absolute processing deadline. Nullable only for jobs created before
     * deadline persistence was introduced.
     */
    private LocalDateTime deadlineAt;

    @Column(length = 80)
    private String errorCode;

    @Column(length = 1000)
    private String safeErrorMessage;

    @Column(columnDefinition = "TEXT")
    private String finalSummary;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    public void transitionTo(SummarizationJobStatus nextStatus) {
        if (status == null) {
            if (nextStatus != SummarizationJobStatus.QUEUED) {
                throw invalidTransition(nextStatus);
            }
        } else if (status == SummarizationJobStatus.QUEUED) {
            if (nextStatus != SummarizationJobStatus.PROCESSING
                    && nextStatus != SummarizationJobStatus.CANCELLED) {
                throw invalidTransition(nextStatus);
            }
        } else if (status == SummarizationJobStatus.PROCESSING) {
            if (nextStatus != SummarizationJobStatus.COMPLETED
                    && nextStatus != SummarizationJobStatus.FAILED
                    && nextStatus != SummarizationJobStatus.CANCELLED) {
                throw invalidTransition(nextStatus);
            }
        } else if (nextStatus != status) {
            throw invalidTransition(nextStatus);
        }

        status = nextStatus;
        if (nextStatus == SummarizationJobStatus.COMPLETED
                || nextStatus == SummarizationJobStatus.FAILED
                || nextStatus == SummarizationJobStatus.CANCELLED) {
            completedAt = LocalDateTime.now();
        }
    }

    public void setProgress(int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException(
                    "Progress must be between 0 and 100."
            );
        }
        this.progress = progress;
    }

    private IllegalStateException invalidTransition(
            SummarizationJobStatus nextStatus) {
        return new IllegalStateException(
                "Invalid summarization job transition from "
                        + status + " to " + nextStatus + "."
        );
    }

    @PrePersist
    private void initializeTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    private void updateTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}

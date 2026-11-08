package com.model.documindai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(
        name = "summarization_batches",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_summarization_batch_job_number",
                columnNames = {"job_id", "batch_number"}
        )
)
public class SummarizationBatch {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private SummarizationJob job;

    @Column(nullable = false)
    private int batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SummarizationBatchStatus status;

    @Column(nullable = false)
    private int firstChunkNumber;

    @Column(nullable = false)
    private int lastChunkNumber;

    @Column(nullable = false)
    private int inputCharacterCount;

    @Column(nullable = false)
    private int outputCharacterCount;

    @Column(columnDefinition = "TEXT")
    private String summaryText;

    @Column(columnDefinition = "TEXT")
    private String sourceMetadata;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private int geminiCallCount;

    @Column(length = 80)
    private String errorCode;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Column(length = 100)
    private String workerId;

    @Column(nullable = false)
    private long leaseGeneration;

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

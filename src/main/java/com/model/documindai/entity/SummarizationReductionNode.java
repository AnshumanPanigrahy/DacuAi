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
        name = "summarization_reduction_nodes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reduction_node_job_level_group",
                columnNames = {"job_id", "level", "group_number"}
        ),
        indexes = {
                @Index(
                        name = "idx_reduction_node_job_status_level",
                        columnList = "job_id,status,level"
                ),
                @Index(
                        name = "idx_reduction_node_claim",
                        columnList = "job_id,status,lease_expires_at"
                )
        }
)
public class SummarizationReductionNode {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private SummarizationJob job;

    @Column(nullable = false)
    private int level;

    @Column(name = "group_number", nullable = false)
    private int groupNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReductionNodeStatus status;

    @Column(name = "child_references", columnDefinition = "TEXT",
            nullable = false)
    private String childReferences;

    @Column(columnDefinition = "TEXT")
    private String summaryText;

    @Column(columnDefinition = "TEXT")
    private String sourceMetadata;

    @Column(nullable = false)
    private int inputCharacterCount;

    @Column(nullable = false)
    private int outputCharacterCount;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private int geminiCallCount;

    @Column(length = 80)
    private String errorCode;

    @Column(length = 1000)
    private String safeErrorMessage;

    @Column(length = 100)
    private String workerId;

    @Column(nullable = false)
    private long leaseGeneration;

    private LocalDateTime leaseExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

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

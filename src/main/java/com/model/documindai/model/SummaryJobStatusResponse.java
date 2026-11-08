package com.model.documindai.model;

import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SummaryJobStatusResponse(
        UUID jobId,
        Long documentId,
        SummarizationJobStatus status,
        int progress,
        SummarizationJobStage processingStage,
        int completedBatchCount,
        int totalBatchCount,
        String errorCode,
        String safeErrorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt) {
}

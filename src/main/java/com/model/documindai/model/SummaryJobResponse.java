package com.model.documindai.model;

import com.model.documindai.entity.SummarizationJobStage;
import com.model.documindai.entity.SummarizationJobStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SummaryJobResponse(
        UUID jobId,
        Long documentId,
        SummarizationJobStatus status,
        int progress,
        SummarizationJobStage processingStage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

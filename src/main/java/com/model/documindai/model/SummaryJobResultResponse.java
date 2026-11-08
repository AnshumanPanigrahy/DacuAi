package com.model.documindai.model;

import com.model.documindai.entity.SummarizationJobStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SummaryJobResultResponse(
        UUID jobId,
        Long documentId,
        SummarizationJobStatus status,
        String summary,
        LocalDateTime completedAt) {
}

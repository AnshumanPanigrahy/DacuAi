package com.model.documindai.entity;

public enum SummarizationJobStage {
    QUEUED,
    LOADING_CHUNKS,
    SUMMARIZING_BATCHES,
    REDUCING_BATCH_SUMMARIES,
    FINALIZING
}

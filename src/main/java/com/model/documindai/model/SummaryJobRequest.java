package com.model.documindai.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SummaryJobRequest(
        @NotNull
        @Positive
        Long documentId) {
}

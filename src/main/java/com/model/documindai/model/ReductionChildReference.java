package com.model.documindai.model;

import java.util.UUID;

public record ReductionChildReference(
        ReferenceType type,
        UUID id) {

    public enum ReferenceType {
        BATCH,
        REDUCTION_NODE
    }
}

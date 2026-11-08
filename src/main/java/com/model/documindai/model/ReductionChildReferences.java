
package com.model.documindai.model;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;


import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ReductionChildReferences(
        int version,
        List<ReductionChildReference> references) {

    public static final int CURRENT_VERSION = 1;

    public ReductionChildReferences {

        // Validate schema version
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported reduction reference version: " + version
            );
        }

        // Validate collection
        if (references == null || references.isEmpty()) {
            throw new IllegalArgumentException(
                    "Reduction nodes require at least one child reference."
            );
        }

        // Validate individual references BEFORE List.copyOf()
        for (ReductionChildReference reference : references) {
            if (reference == null
                    || reference.type() == null
                    || reference.id() == null) {
                throw new IllegalArgumentException(
                        "Reduction child references must contain a type and ID."
                );
            }
        }

        // Check duplicate references
        Set<ReductionChildReference> unique = new HashSet<>(references);

        if (unique.size() != references.size()) {
            throw new IllegalArgumentException(
                    "Reduction child references must be unique."
            );
        }

        // Create immutable defensive copy while preserving order
        references = List.copyOf(references);
    }

    public static ReductionChildReferences of(
            List<ReductionChildReference> references) {

        return new ReductionChildReferences(
                CURRENT_VERSION,
                references
        );
    }

    public String toJson(JsonMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JacksonException error) {
            throw new IllegalStateException(
                    "Unable to serialize reduction child references.",
                    error
            );
        }
    }

    public static ReductionChildReferences fromJson(
            JsonMapper objectMapper, String json) {

        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                    "Reduction child references are required."
            );
        }

        try {
            return objectMapper.readValue(
                    json,
                    ReductionChildReferences.class
            );
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "Invalid reduction child reference JSON.",
                    error
            );
        }
    }
}

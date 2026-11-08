
package com.model.documindai.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReductionChildReferencesTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldCreateValidReferences() {
        UUID batchId = UUID.randomUUID();

        ReductionChildReferences references =
                ReductionChildReferences.of(
                        List.of(
                                new ReductionChildReference(
                                        ReductionChildReference.ReferenceType.BATCH,
                                        batchId
                                )
                        )
                );

        assertEquals(1, references.version());
        assertEquals(1, references.references().size());
        assertEquals(batchId, references.references().get(0).id());
    }

    @Test
    void shouldRejectNullReferenceList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReductionChildReferences.of(null)
        );
    }

    @Test
    void shouldRejectEmptyReferenceList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReductionChildReferences.of(List.of())
        );
    }

    @Test
    void shouldRejectNullReferenceElement() {
        List<ReductionChildReference> references =
                Arrays.asList((ReductionChildReference) null);

        assertThrows(
                IllegalArgumentException.class,
                () -> ReductionChildReferences.of(references)
        );
    }

    @Test
    void shouldRejectDuplicateReferences() {
        UUID batchId = UUID.randomUUID();

        ReductionChildReference reference =
                new ReductionChildReference(
                        ReductionChildReference.ReferenceType.BATCH,
                        batchId
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> ReductionChildReferences.of(
                        List.of(reference, reference)
                )
        );
    }

    @Test
    void shouldRejectUnsupportedVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReductionChildReferences(
                        2,
                        List.of(
                                new ReductionChildReference(
                                        ReductionChildReference.ReferenceType.BATCH,
                                        UUID.randomUUID()
                                )
                        )
                )
        );
    }

    @Test
    void shouldSerializeAndDeserializeReferences() {
        UUID batchId = UUID.randomUUID();

        ReductionChildReferences original =
                ReductionChildReferences.of(
                        List.of(
                                new ReductionChildReference(
                                        ReductionChildReference.ReferenceType.BATCH,
                                        batchId
                                )
                        )
                );

        String json = original.toJson(objectMapper);

        ReductionChildReferences restored =
                ReductionChildReferences.fromJson(
                        objectMapper,
                        json
                );

        assertEquals(original, restored);
    }

    @Test
    void shouldPreserveReferenceOrder() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        ReductionChildReferences references =
                ReductionChildReferences.of(
                        List.of(
                                new ReductionChildReference(
                                        ReductionChildReference.ReferenceType.BATCH,
                                        firstId
                                ),
                                new ReductionChildReference(
                                        ReductionChildReference.ReferenceType.BATCH,
                                        secondId
                                )
                        )
                );

        assertEquals(firstId, references.references().get(0).id());
        assertEquals(secondId, references.references().get(1).id());
    }

    @Test
    void shouldRejectBlankJson() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReductionChildReferences.fromJson(
                        objectMapper,
                        " "
                )
        );
    }
}

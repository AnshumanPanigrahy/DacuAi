
package com.model.documindai.service;

import tools.jackson.databind.json.JsonMapper;
import com.model.documindai.entity.*;
import com.model.documindai.model.ReductionChildReference;
import com.model.documindai.model.ReductionChildReferences;
import com.model.documindai.repository.SummarizationBatchRepository;
import com.model.documindai.repository.SummarizationJobRepository;
import com.model.documindai.repository.SummarizationReductionNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class SummarizationReductionNodeService {

    private final SummarizationJobRepository jobRepository;
    private final SummarizationBatchRepository batchRepository;
    private final SummarizationReductionNodeRepository nodeRepository;
    private final JsonMapper objectMapper;

    public SummarizationReductionNodeService(
            SummarizationJobRepository jobRepository,
            SummarizationBatchRepository batchRepository,
            SummarizationReductionNodeRepository nodeRepository,
            JsonMapper objectMapper) {

        this.jobRepository = jobRepository;
        this.batchRepository = batchRepository;
        this.nodeRepository = nodeRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SummarizationReductionNode saveValidated(
            SummarizationReductionNode node,
            ReductionChildReferences references) {

        if (node == null || node.getJob() == null
                || node.getJob().getId() == null) {
            throw new IllegalArgumentException(
                    "A valid parent job is required."
            );
        }

        if (references == null) {
            throw new IllegalArgumentException(
                    "Child references are required."
            );
        }

        UUID jobId = node.getJob().getId();

        SummarizationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parent summarization job does not exist."
                ));

        if (node.getId() != null) {
            if (nodeRepository.existsById(node.getId())) {
                throw new IllegalArgumentException(
                        "This service only creates new reduction nodes."
                );
            }

            if (references.references().stream()
                    .anyMatch(ref -> ref.id().equals(node.getId()))) {
                throw new IllegalArgumentException(
                        "A reduction node cannot reference itself."
                );
            }
        }

        int level = node.getLevel();

        if (level < 0 || node.getGroupNumber() < 0) {
            throw new IllegalArgumentException(
                    "Reduction level and group number cannot be negative."
            );
        }

        if (node.getInputCharacterCount() < 0) {
            throw new IllegalArgumentException(
                    "Input character count cannot be negative."
            );
        }

        for (ReductionChildReference reference :
                references.references()) {

            if (level == 0) {
                validateBatchReference(jobId, reference);
            } else {
                validateReductionReference(jobId, level, reference);
            }
        }

        node.setJob(job);
        node.setStatus(ReductionNodeStatus.PENDING);
        node.setChildReferences(references.toJson(objectMapper));

        // New nodes must not inherit execution state.
        node.setSummaryText(null);
        node.setSourceMetadata(null);
        node.setOutputCharacterCount(0);
        node.setAttemptCount(0);
        node.setGeminiCallCount(0);
        node.setErrorCode(null);
        node.setSafeErrorMessage(null);
        node.setWorkerId(null);
        node.setLeaseGeneration(0);
        node.setLeaseExpiresAt(null);
        node.setCompletedAt(null);

        return nodeRepository.saveAndFlush(node);
    }

    private void validateBatchReference(
            UUID jobId,
            ReductionChildReference reference) {

        if (reference.type()
                != ReductionChildReference.ReferenceType.BATCH) {
            throw new IllegalArgumentException(
                    "Level-zero nodes must reference batches."
            );
        }

        SummarizationBatch batch = batchRepository
                .findById(reference.id())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Referenced batch does not exist."
                ));

        if (!jobId.equals(batch.getJob().getId())) {
            throw new IllegalArgumentException(
                    "Referenced batch belongs to another job."
            );
        }

        if (batch.getStatus() != SummarizationBatchStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Only completed batches can be referenced."
            );
        }
    }

    private void validateReductionReference(
            UUID jobId,
            int currentLevel,
            ReductionChildReference reference) {

        if (reference.type()
                != ReductionChildReference.ReferenceType.REDUCTION_NODE) {
            throw new IllegalArgumentException(
                    "Higher-level nodes must reference reduction nodes."
            );
        }

        SummarizationReductionNode child = nodeRepository
                .findById(reference.id())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Referenced reduction node does not exist."
                ));

        if (!jobId.equals(child.getJob().getId())) {
            throw new IllegalArgumentException(
                    "Referenced reduction node belongs to another job."
            );
        }

        if (child.getId().equals(reference.id())) {
            throw new IllegalArgumentException(
                    "A reduction node cannot reference itself."
            );
        }

        if (child.getLevel() != currentLevel - 1) {
            throw new IllegalArgumentException(
                    "A reduction node must reference the immediately preceding level."
            );
        }

        if (child.getStatus() != ReductionNodeStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "Only completed reduction nodes can be referenced."
            );
        }
    }
}


package com.model.documindai.repository;

import com.model.documindai.entity.ReductionNodeStatus;
import com.model.documindai.entity.SummarizationReductionNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SummarizationReductionNodeRepository
        extends JpaRepository<SummarizationReductionNode, UUID> {

    // Retrieve every reduction node belonging to a job.
    List<SummarizationReductionNode>
    findByJob_IdOrderByLevelAscGroupNumberAsc(UUID jobId);

    // Retrieve nodes at a particular reduction level.
    List<SummarizationReductionNode>
    findByJob_IdAndLevelOrderByGroupNumberAsc(
            UUID jobId,
            int level
    );

    // Count nodes by level and status.
    long countByJob_IdAndLevelAndStatus(
            UUID jobId,
            int level,
            ReductionNodeStatus status
    );

    // Retrieve nodes by status.
    List<SummarizationReductionNode>
    findByJob_IdAndStatusOrderByLevelAscGroupNumberAsc(
            UUID jobId,
            ReductionNodeStatus status
    );

    // Check whether a group already exists at a particular level.
    boolean existsByJob_IdAndLevelAndGroupNumber(
            UUID jobId,
            int level,
            int groupNumber
    );
}

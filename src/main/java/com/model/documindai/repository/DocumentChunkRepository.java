package com.model.documindai.repository;

import com.model.documindai.entity.DocumentChunk;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

@Repository
public interface DocumentChunkRepository
        extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByChunkTextContainingIgnoreCase(String keyword);

    @EntityGraph(attributePaths = "pageNumbers")
    List<DocumentChunk> findByIdIn(Collection<Long> ids);

    @EntityGraph(attributePaths = "pageNumbers")
    List<DocumentChunk> findByDocumentIdAndChunkNumberBetween(
            Long documentId,
            int startChunk,
            int endChunk
    );

    @EntityGraph(attributePaths = "pageNumbers")
    @Query("""
            select chunk
              from DocumentChunk chunk
             where chunk.document.id = :documentId
               and (chunk.chunkNumber > :chunkNumber
                    or (chunk.chunkNumber = :chunkNumber
                        and chunk.id > :chunkId))
             order by chunk.chunkNumber asc, chunk.id asc
            """)
    List<DocumentChunk> findNextPage(
            @Param("documentId") Long documentId,
            @Param("chunkNumber") int chunkNumber,
            @Param("chunkId") long chunkId,
            Pageable pageable
    );

    @EntityGraph(attributePaths = "pageNumbers")
    List<DocumentChunk> findByDocument_IdOrderByChunkNumberAsc(
            Long documentId
    );

    long countByDocument_Id(Long documentId);
}
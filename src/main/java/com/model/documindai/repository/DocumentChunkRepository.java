package com.model.documindai.repository;

import com.model.documindai.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository
        extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByChunkTextContainingIgnoreCase(String keyword);

    List<DocumentChunk> findByDocumentIdAndChunkNumberBetween(
            Long documentId,
            int startChunk,
            int endChunk
    );
}
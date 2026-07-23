package com.model.documindai.service;

import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    private final DocumentChunkRepository documentChunkRepository;

    public SearchService(DocumentChunkRepository documentChunkRepository) {
        this.documentChunkRepository = documentChunkRepository;
    }

    public List<String> search(String keyword) {

        List<DocumentChunk> documentChunks =
                documentChunkRepository.findByChunkTextContainingIgnoreCase(keyword);

        List<String> results = new ArrayList<>();

        for (DocumentChunk chunk : documentChunks) {
            results.add(chunk.getChunkText());
        }

        return results;
    }
}
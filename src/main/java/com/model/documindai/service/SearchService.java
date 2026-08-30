package com.model.documindai.service;

import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.model.SearchResult;
import com.model.documindai.repository.DocumentChunkRepository;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class SearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentChunkRepository documentChunkRepository;

    public SearchService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            DocumentChunkRepository documentChunkRepository) {

        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentChunkRepository = documentChunkRepository;
    }

    public List<SearchResult> search(String query) {

        // 1. Convert question into an embedding
        Embedding queryEmbedding =
                embeddingModel.embed(query).content();

        // 2. Retrieve candidate chunks
        EmbeddingSearchRequest request =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(10)
                        .build();

        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request).matches();

        // 3. Convert vector matches to SearchResult
        List<SearchResult> semanticResults = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : matches) {

            // Ignore weak matches
            if (match.score() < 0.80) {
                continue;
            }

            TextSegment segment = match.embedded();

            String documentId =
                    segment.metadata().getString("documentId");

            String chunkId =
                    segment.metadata().getString("chunkId");

            String chunkNumber =
                    segment.metadata().getString("chunkNumber");

            semanticResults.add(
                    new SearchResult(
                            match.score(),
                            documentId,
                            chunkId,
                            chunkNumber,
                            segment.text()
                    )
            );
        }

        // Strongest result first
        semanticResults.sort(
                Comparator.comparingDouble(
                        SearchResult::getScore
                ).reversed()
        );

        // Keep the strongest semantic matches
        List<SearchResult> topResults =
                semanticResults.stream()
                        .limit(5)
                        .toList();

        if (topResults.isEmpty()) {
            return List.of();
        }

        // 4. Group nearby chunks
        return buildRelevantContext(topResults);
    }

    private List<SearchResult> buildRelevantContext(
            List<SearchResult> topResults) {

        if (topResults.isEmpty()) {
            return List.of();
        }

        // Strongest semantic result
        SearchResult strongest = topResults.get(0);

        try {
            Long documentId =
                    Long.parseLong(strongest.getDocumentId());

            int center =
                    Integer.parseInt(strongest.getChunkNumber());

            List<DocumentChunk> region =
                    documentChunkRepository
                            .findByDocumentIdAndChunkNumberBetween(
                                    documentId,
                                    Math.max(1, center - 2),
                                    center + 2
                            );

            List<SearchResult> finalResults =
                    new ArrayList<>();

            for (DocumentChunk chunk : region) {

                double score = 0.0;

                /*
                 * If this chunk was directly returned
                 * by vector search, preserve its real score.
                 */
                for (SearchResult result : topResults) {

                    if (result.getChunkId()
                            .equals(String.valueOf(chunk.getId()))) {

                        score = result.getScore();
                        break;
                    }
                }

                finalResults.add(
                        new SearchResult(
                                score,
                                String.valueOf(documentId),
                                String.valueOf(chunk.getId()),
                                String.valueOf(chunk.getChunkNumber()),
                                chunk.getChunkText()
                        )
                );
            }

            return finalResults;

        } catch (NumberFormatException e) {

            System.out.print(
                    "Invalid document/chunk metadata"
            );

            return topResults;
        }
    }
}
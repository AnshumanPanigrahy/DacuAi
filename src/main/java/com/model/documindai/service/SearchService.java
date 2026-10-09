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
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;

@Service
public class SearchService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentChunkRepository documentChunkRepository;

    private static final double SIMILARITY_THRESHOLD = 0.60;
    private static final int CANDIDATE_COUNT = 100;
    private static final int CONTEXT_RADIUS = 2;
    private static final int FINAL_RESULT_COUNT = 5;

    public SearchService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            DocumentChunkRepository documentChunkRepository) {

        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.documentChunkRepository = documentChunkRepository;
    }

    public List<SearchResult> search(String query) {

        // 1. Convert the question into an embedding
        Embedding queryEmbedding =
                embeddingModel.embed(query).content();

        // 2. Search pgvector
        EmbeddingSearchRequest request =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(CANDIDATE_COUNT)
                        .build();

        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request).matches();

        // 3. Convert semantic matches
        List<SearchResult> semanticResults =
                new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : matches) {

            // Ignore weak semantic matches
            if (match.score() < SIMILARITY_THRESHOLD) {
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
                            segment.text(),
                            "SEMANTIC"
                    )
            );
        }

        // 4. Strongest semantic result first
        semanticResults.sort(
                Comparator.comparingDouble(
                        SearchResult::getScore
                ).reversed()
        );

        if (semanticResults.isEmpty()) {
            return List.of();
        }

        // 5. Add neighboring chunks for context
        List<SearchResult> contextualResults =
                buildRelevantContext(semanticResults);

        // 6. Remove duplicate chunks
        List<SearchResult> uniqueResults =
                removeDuplicates(contextualResults);

        // 7. Limit final context
        return uniqueResults.stream()
                .limit(FINAL_RESULT_COUNT)
                .toList();
    }

    private List<SearchResult> buildRelevantContext(
            List<SearchResult> semanticResults) {

        List<SearchResult> finalResults = new ArrayList<>();

        /*
         * Only expand context around the strongest semantic results.
         * This prevents weak matches from filling the final result list.
         */
        int semanticLimit = Math.min(3, semanticResults.size());

        for (int i = 0; i < semanticLimit; i++) {

            SearchResult semanticResult =
                    semanticResults.get(i);

            try {

                Long documentId =
                        Long.parseLong(
                                semanticResult.getDocumentId()
                        );

                int center =
                        Integer.parseInt(
                                semanticResult.getChunkNumber()
                        );

                List<DocumentChunk> region =
                        documentChunkRepository
                                .findByDocumentIdAndChunkNumberBetween(
                                        documentId,
                                        Math.max(
                                                1,
                                                center - CONTEXT_RADIUS
                                        ),
                                        center + CONTEXT_RADIUS
                                );

                for (DocumentChunk chunk : region) {

                    double score =
                            findOriginalScore(
                                    semanticResults,
                                    chunk
                            );

                    String retrievalType =
                            score > 0.0
                                    ? "SEMANTIC"
                                    : "CONTEXT";

                    finalResults.add(
                            new SearchResult(
                                    score,
                                    String.valueOf(documentId),
                                    String.valueOf(chunk.getId()),
                                    String.valueOf(
                                            chunk.getChunkNumber()
                                    ),
                                    chunk.getChunkText(),
                                    retrievalType
                            )
                    );
                }

            } catch (NumberFormatException e) {

                System.out.println(
                        "Invalid document/chunk metadata"
                );
            }
        }

        return finalResults;
    }

    private double findOriginalScore(
            List<SearchResult> semanticResults,
            DocumentChunk chunk) {

        for (SearchResult result : semanticResults) {

            if (result.getChunkId()
                    .equals(String.valueOf(chunk.getId()))) {

                return result.getScore();
            }
        }

        return 0.0;
    }

    private List<SearchResult> removeDuplicates(
            List<SearchResult> results) {

        List<SearchResult> uniqueResults =
                new ArrayList<>();

        for (SearchResult result : results) {

            boolean duplicate =
                    uniqueResults.stream()
                            .anyMatch(existing ->
                                    existing.getChunkId()
                                            .equals(
                                                    result.getChunkId()
                                            )
                            );

            if (!duplicate) {
                uniqueResults.add(result);
            }
        }

        return uniqueResults;
    }
    public List<SearchResult> searchByDocument(
            String query,
            Long documentId) {

        Embedding queryEmbedding =
                embeddingModel.embed(query).content();

        Filter documentFilter =
                MetadataFilterBuilder
                        .metadataKey("documentId")
                        .isEqualTo(documentId);

        EmbeddingSearchRequest request =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .filter(documentFilter)
                        .maxResults(CANDIDATE_COUNT)
                        .build();

        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request).matches();

        List<SearchResult> semanticResults =
                new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : matches) {

            if (match.score() < SIMILARITY_THRESHOLD) {
                continue;
            }

            TextSegment segment = match.embedded();

            String chunkId =
                    segment.metadata().getString("chunkId");

            String chunkNumber =
                    segment.metadata().getString("chunkNumber");

            semanticResults.add(
                    new SearchResult(
                            match.score(),
                            String.valueOf(documentId),
                            chunkId,
                            chunkNumber,
                            segment.text(),
                            "SEMANTIC"
                    )
            );
        }

        semanticResults.sort(
                Comparator.comparingDouble(
                        SearchResult::getScore
                ).reversed()
        );

        if (semanticResults.isEmpty()) {
            return List.of();
        }

        List<SearchResult> contextualResults =
                buildRelevantContext(semanticResults);

        return removeDuplicates(contextualResults)
                .stream()
                .limit(FINAL_RESULT_COUNT)
                .toList();
    }
}
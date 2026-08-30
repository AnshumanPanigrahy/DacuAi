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

    private static final double SIMILARITY_THRESHOLD = 0.80;
    private static final int CANDIDATE_COUNT = 10;
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

        // 1. Convert question into embedding
        Embedding queryEmbedding =
                embeddingModel.embed(query).content();

        // 2. Retrieve more candidates
        EmbeddingSearchRequest request =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(CANDIDATE_COUNT)
                        .build();

        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request).matches();


        List<SearchResult> semanticResults = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : matches) {

            // Remove weak semantic matches
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
                            segment.text()
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


        List<SearchResult> finalResults =
                removeDuplicates(contextualResults);


        return finalResults.stream()
                .limit(FINAL_RESULT_COUNT)
                .toList();
    }

    private List<SearchResult> buildRelevantContext(
            List<SearchResult> semanticResults) {

        SearchResult strongest =
                semanticResults.get(0);

        try {

            Long documentId =
                    Long.parseLong(strongest.getDocumentId());

            int center =
                    Integer.parseInt(
                            strongest.getChunkNumber()
                    );

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

                double score =
                        findOriginalScore(
                                semanticResults,
                                chunk
                        );

                if (score == 0.0) {
                    score = strongest.getScore() * 0.95;
                }

                finalResults.add(
                        new SearchResult(
                                score,
                                String.valueOf(documentId),
                                String.valueOf(chunk.getId()),
                                String.valueOf(
                                        chunk.getChunkNumber()
                                ),
                                chunk.getChunkText()
                        )
                );
            }

            finalResults.sort(
                    Comparator.comparingInt(
                            result -> Integer.parseInt(
                                    result.getChunkNumber()
                            )
                    )
            );

            return finalResults;

        } catch (NumberFormatException e) {

            System.out.print(
                    "Invalid document/chunk metadata"
            );

            return semanticResults.stream()
                    .limit(FINAL_RESULT_COUNT)
                    .toList();
        }
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

            boolean duplicate = uniqueResults.stream()
                    .anyMatch(existing ->
                            existing.getChunkId()
                                    .equals(result.getChunkId())
                    );

            if (!duplicate) {
                uniqueResults.add(result);
            }
        }

        return uniqueResults;
    }
}
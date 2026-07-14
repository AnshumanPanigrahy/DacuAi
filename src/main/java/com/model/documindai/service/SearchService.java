package com.model.documindai.service;

import com.model.documindai.model.VectorDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    private final List<VectorDocument> vectorDocuments = new ArrayList<>();

    private final EmbeddingService embeddingService;
    private final SimilarityService similarityService;

    public SearchService(EmbeddingService embeddingService,
                         SimilarityService similarityService) {

        this.embeddingService = embeddingService;
        this.similarityService = similarityService;
    }

    public void saveChunks(List<String> chunks) {

        for (String chunk : chunks) {

            double[] vector =
                    embeddingService.generateEmbedding(chunk);

            vectorDocuments.add(
                    new VectorDocument(chunk, vector)
            );
        }
    }

    public List<String> semanticSearch(String query) {

        double[] queryVector =
                embeddingService.generateEmbedding(query);

        List<String> results = new ArrayList<>();

        double bestScore = -1;
        String bestChunk = "";

        for (VectorDocument doc : vectorDocuments) {

            double score =
                    similarityService.cosineSimilarity(
                            queryVector,
                            doc.getVector()
                    );

            if (score > bestScore) {

                bestScore = score;
                bestChunk = doc.getText();
            }
        }

        if (!bestChunk.isEmpty()) {
            results.add(bestChunk);
        }

        return results;
    }
}
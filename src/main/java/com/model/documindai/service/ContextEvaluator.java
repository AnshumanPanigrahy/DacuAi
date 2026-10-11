package com.model.documindai.service;

import com.model.documindai.model.ContextEvaluationResult;
import com.model.documindai.model.SearchResult;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContextEvaluator {

    private static final double HIGH_SCORE = 0.85;
    private static final double MEDIUM_SCORE = 0.80;

    public ContextEvaluationResult evaluate(
            String question,
            List<SearchResult> results) {

        // No relevant context
        if (results == null || results.isEmpty()) {

            return new ContextEvaluationResult(
                    false,
                    0.0,
                    "No relevant document context was found."
            );
        }

        // Find the strongest semantic result
        double strongestScore =
                results.stream()
                        .filter(result ->
                                "SEMANTIC".equals(
                                        result.getRetrievalType()
                                )
                        )
                        .mapToDouble(SearchResult::getScore)
                        .max()
                        .orElse(0.0);

        /*
         * Count how many chunks were directly retrieved
         * through semantic search.
         */
        long semanticChunks =
                results.stream()
                        .filter(result ->
                                "SEMANTIC".equals(
                                        result.getRetrievalType()
                                )
                        )
                        .count();

        if (strongestScore >= HIGH_SCORE &&
                semanticChunks >= 1) {

            return new ContextEvaluationResult(
                    true,
                    strongestScore,
                    "The document contains a strong semantic match."
            );
        }


        if (strongestScore >= MEDIUM_SCORE &&
                semanticChunks >= 2) {

            return new ContextEvaluationResult(
                    true,
                    strongestScore,
                    "Multiple relevant document chunks were found."
            );
        }

        return new ContextEvaluationResult(
                false,
                strongestScore,
                "The retrieved document context may not contain enough information to answer the question."
        );
    }
}
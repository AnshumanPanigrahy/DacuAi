package com.model.documindai.service;

import com.model.documindai.model.WebSearchResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebResultEvaluator {

    private static final double MIN_WEB_SCORE = 0.70;

    public List<WebSearchResult> filter(
            List<WebSearchResult> results) {

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        return results.stream()
                .filter(result ->
                        result.getScore() >= MIN_WEB_SCORE
                )
                .limit(5)
                .toList();
    }
}
package com.model.documindai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.documindai.model.WebSearchResponse;
import com.model.documindai.model.WebSearchResult;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class WebSearchService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WebSearchService() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public WebSearchResponse search(String query) {

        String apiKey = System.getenv("TAVILY_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "TAVILY_API_KEY environment variable is not set"
            );
        }

        String jsonBody = """
                {
                  "query": "%s",
                  "search_depth": "basic",
                  "topic": "general",
                  "max_results": 5
                }
                """.formatted(
                escapeJson(query)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(
                        HttpRequest.BodyPublishers
                                .ofString(jsonBody)
                )
                .build();

        try {

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() < 200 ||
                    response.statusCode() >= 300) {

                throw new RuntimeException(
                        "Tavily API error: HTTP "
                                + response.statusCode()
                                + " - "
                                + response.body()
                );
            }

            return parseResponse(
                    query,
                    response.body()
            );

        } catch (Exception e) {

            throw new RuntimeException(
                    "Web search failed",
                    e
            );
        }
    }

    private WebSearchResponse parseResponse(
            String query,
            String json) throws Exception {

        JsonNode root =
                objectMapper.readTree(json);

        JsonNode resultsNode =
                root.path("results");

        List<WebSearchResult> results =
                new ArrayList<>();

        for (JsonNode resultNode : resultsNode) {

            String title =
                    resultNode.path("title").asText();

            String url =
                    resultNode.path("url").asText();

            String content =
                    resultNode.path("content").asText();

            double score =
                    resultNode.path("score").asDouble();

            results.add(
                    new WebSearchResult(
                            title,
                            url,
                            content,
                            score
                    )
            );
        }

        return new WebSearchResponse(
                query,
                results
        );
    }

    private String escapeJson(String text) {

        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
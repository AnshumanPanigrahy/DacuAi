package com.model.documindai.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class WebSearchService {

    private final HttpClient httpClient;

    public WebSearchService() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public String search(String query) {

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

            return response.body();

        } catch (Exception e) {

            throw new RuntimeException(
                    "Web search failed",
                    e
            );
        }
    }

    private String escapeJson(String text) {

        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
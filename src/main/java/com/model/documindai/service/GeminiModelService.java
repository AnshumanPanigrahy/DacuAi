package com.model.documindai.service;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GeminiModelService {

    private final List<String> apiKeys = new ArrayList<>();

    public GeminiModelService() {

        addKey(System.getenv("GEMINI_API_KEY_1"));
        addKey(System.getenv("GEMINI_API_KEY_2"));
        addKey(System.getenv("GEMINI_API_KEY_3"));

        // Original key as fallback
        addKey(System.getenv("GEMINI_API_KEY"));

        if (apiKeys.isEmpty()) {
            throw new IllegalStateException(
                    "No Gemini API key is configured."
            );
        }
    }

    private void addKey(String key) {

        if (key != null && !key.isBlank()) {

            if (!apiKeys.contains(key)) {
                apiKeys.add(key);
            }
        }
    }

    public List<StreamingChatModel> getModels() {

        List<StreamingChatModel> models =
                new ArrayList<>();

        for (String apiKey : apiKeys) {

            models.add(
                    GoogleAiGeminiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .modelName("gemini-3.6-flash")
                            .build()
            );
        }

        return models;
    }
}
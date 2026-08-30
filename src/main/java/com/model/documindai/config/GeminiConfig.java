package com.model.documindai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeminiConfig {

    private String getApiKey() {

        String apiKey = System.getenv("GEMINI_API_KEY");

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "GEMINI_API_KEY environment variable is not set"
            );
        }

        return apiKey;
    }

    @Bean
    public ChatModel chatModel() {

        return GoogleAiGeminiChatModel.builder()
                .apiKey(getApiKey())
                .modelName("gemini-3.6-flash")
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {

        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(getApiKey())
                .modelName("gemini-3.6-flash")
                .build();
    }
}
package com.model.documindai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GeminiConfig {

    @Bean
    public ChatModel chatModel() {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY1"))
                .modelName("gemini-3.6-flash")
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(System.getenv("GEMINI_API_KEY1"))
                .modelName("gemini-3.6-flash")
                .build();
    }
}
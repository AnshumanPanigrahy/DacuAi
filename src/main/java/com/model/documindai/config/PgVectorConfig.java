package com.model.documindai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PgVectorConfig {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            EmbeddingModel embeddingModel) {

        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5433)
                .database("documind")
                .user("postgres")
                .password("postgres")
                .table("document_embeddings")
                .dimension(embeddingModel.dimension())
                .build();
    }
}
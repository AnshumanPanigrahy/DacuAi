package com.model.documindai.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

@Service
public class VectorStoreService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public VectorStoreService(
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore) {

        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    public String storeTestText(String text) {

        TextSegment segment = TextSegment.from(
                text,
                Metadata.from("source", "test")
        );

        embeddingStore.add(
                embeddingModel.embed(segment).content(),
                segment
        );

        return "Embedding stored successfully";
    }
}
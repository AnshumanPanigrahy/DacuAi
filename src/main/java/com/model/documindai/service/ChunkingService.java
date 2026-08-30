package com.model.documindai.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChunkingService {

    public List<TextSegment> createChunks(String text) {

        Document document = Document.from(text);

        return DocumentSplitters.recursive(
                500,
                100
        ).split(document);
    }
}
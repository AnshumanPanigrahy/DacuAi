package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.DocumentRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore) {

        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    public Document saveDocument(String fileName) {

        Optional<Document> existingDocument =
                documentRepository.findByFileName(fileName);

        if (existingDocument.isPresent()) {
            throw new RuntimeException("Document already exists.");
        }

        Document document = new Document();

        document.setTitle(fileName);
        document.setFileName(fileName);
        document.setUploadTime(LocalDateTime.now());

        return documentRepository.save(document);
    }

    public void saveChunks(Document document, List<String> chunks) {

        int chunkNumber = 1;

        for (String chunk : chunks) {

            /*
             * Clean the extracted PDF text
             */
            String cleanChunk = chunk
                    .replace("\u0000", "")
                    .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                    .trim();

            if (cleanChunk.isEmpty()) {
                continue;
            }

            /*
             * 1. Save the chunk in PostgreSQL
             */
            DocumentChunk documentChunk = new DocumentChunk();

            documentChunk.setChunkNumber(chunkNumber);
            documentChunk.setChunkText(cleanChunk);
            documentChunk.setDocument(document);

            DocumentChunk savedChunk =
                    documentChunkRepository.save(documentChunk);

            /*
             * 2. Create metadata for the vector
             */
            Map<String, String> metadataMap = new HashMap<>();

            metadataMap.put(
                    "documentId",
                    document.getId().toString()
            );

            metadataMap.put(
                    "chunkId",
                    savedChunk.getId().toString()
            );

            metadataMap.put(
                    "chunkNumber",
                    String.valueOf(chunkNumber)
            );

            Metadata metadata = Metadata.from(metadataMap);

            /*
             * 3. Create LangChain4j TextSegment
             */
            TextSegment textSegment =
                    TextSegment.from(cleanChunk, metadata);

            /*
             * 4. Generate real ML embedding
             */
            Embedding embedding =
                    embeddingModel.embed(textSegment).content();

            /*
             * 5. Store embedding in PostgreSQL + pgvector
             */
            embeddingStore.add(
                    embedding,
                    textSegment
            );

            chunkNumber++;
        }
    }

    public List<Document> getAllDocuments() {
        return documentRepository.findAll();
    }

    public Document getDocumentById(Long id) {

        return documentRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Document not found"));
    }

    public void deleteDocument(Long id) {

        Document document =
                documentRepository.findById(id)
                        .orElseThrow(() ->
                                new RuntimeException("Document not found"));

        documentRepository.delete(document);
    }

    public Map<String, Long> getStatistics() {

        Map<String, Long> statistics = new HashMap<>();

        statistics.put(
                "totalDocuments",
                documentRepository.count()
        );

        statistics.put(
                "totalChunks",
                documentChunkRepository.count()
        );

        return statistics;
    }
}
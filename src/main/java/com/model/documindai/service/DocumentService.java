package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.model.PageAwareChunk;
import com.model.documindai.model.DocumentResponse;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.DocumentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.NoSuchFileException;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final PdfStorageService pdfStorageService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository,
            PdfStorageService pdfStorageService,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore) {

        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.pdfStorageService = pdfStorageService;
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

    @Transactional
    public Document saveDocumentWithPdf(
            String fileName,
            byte[] pdfBytes) {

        Document document = saveDocument(fileName);
        String storageKey = null;

        try {
            storageKey = pdfStorageService.store(
                    document.getId(),
                    pdfBytes
            );

            document.setPdfStorageKey(storageKey);
            return documentRepository.save(document);
        } catch (IOException | RuntimeException e) {
            if (storageKey != null) {
                try {
                    pdfStorageService.delete(storageKey);
                } catch (IOException cleanupException) {
                    e.addSuppressed(cleanupException);
                }
            }

            throw new IllegalStateException(
                    "Failed to persist the uploaded PDF.",
                    e
            );
        }
    }

    public byte[] getOriginalPdf(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Document not found."
                ));

        String storageKey = document.getPdfStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stored PDF not found."
            );
        }

        try {
            return pdfStorageService.load(storageKey);
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stored PDF not found.",
                    e
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to read the stored PDF.",
                    e
            );
        }
    }

    public void saveChunks(Document document, List<String> chunks) {

        int chunkNumber = 1;

        for (String chunk : chunks) {
            if (saveChunk(document, chunk, chunkNumber, List.of())) {
                chunkNumber++;
            }
        }
    }

    public void savePageAwareChunks(
            Document document,
            List<PageAwareChunk> chunks) {

        int chunkNumber = 1;

        for (PageAwareChunk chunk : chunks) {
            if (saveChunk(
                    document,
                    chunk.text(),
                    chunkNumber,
                    chunk.pageNumbers()
            )) {
                chunkNumber++;
            }
        }
    }

    private boolean saveChunk(
            Document document,
            String chunk,
            int chunkNumber,
            List<Integer> pageNumbers) {

        /*
         * Clean the extracted PDF text
         */
        String cleanChunk = chunk
                .replace("\u0000", "")
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .trim();

        if (cleanChunk.isEmpty()) {
            return false;
        }

        /*
         * 1. Save the chunk in PostgreSQL
         */
        DocumentChunk documentChunk = new DocumentChunk();

        documentChunk.setChunkNumber(chunkNumber);
        documentChunk.setChunkText(cleanChunk);
        documentChunk.setDocument(document);
        documentChunk.getPageNumbers().addAll(pageNumbers);

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

        return true;
    }

    public List<DocumentResponse> getAllDocuments() {
        return documentRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public DocumentResponse getDocumentById(Long id) {

        Document document = documentRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Document not found"));

        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentChunk> getAllChunks(Long documentId) {

        documentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Document not found."
                ));

        return documentChunkRepository
                .findByDocument_IdOrderByChunkNumberAsc(documentId);
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

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                String.valueOf(document.getId()),
                document.getFileName(),
                document.getUploadTime(),
                documentChunkRepository.countByDocument_Id(document.getId())
        );
    }
}
package com.model.documindai.service;
import com.model.documindai.entity.Document;
import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.DocumentRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Service
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentService(DocumentRepository documentRepository,
                           DocumentChunkRepository documentChunkRepository) {

        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
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
            String cleanChunk = chunk
                    .replace("\u0000", "")
                    .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                    .trim();
            if (cleanChunk.isEmpty()) {
                continue;
            }
            DocumentChunk documentChunk = new DocumentChunk();

            documentChunk.setChunkNumber(chunkNumber++);
            documentChunk.setChunkText(cleanChunk);
            documentChunk.setDocument(document);

            documentChunkRepository.save(documentChunk);
        }
    }

    public List<Document> getAllDocuments() {
        return documentRepository.findAll();
    }

    public Document getDocumentById(Long id) {

        return documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));

    }

    public void deleteDocument(Long id) {

        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Document not found"));

        documentRepository.delete(document);
    }

    public Map<String, Long> getStatistics() {

        Map<String, Long> statistics = new HashMap<>();

        statistics.put("totalDocuments", documentRepository.count());
        statistics.put("totalChunks", documentChunkRepository.count());

        return statistics;
    }
}
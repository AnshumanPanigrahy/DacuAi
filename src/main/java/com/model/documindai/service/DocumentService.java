package com.model.documindai.service;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.DocumentRepository;
import org.springframework.stereotype.Service;


@Service
public class DocumentService {
    private final DocumentRepository  documentRepository;
    private final DocumentChunkRepository  documentChunkRepository;

    public DocumentService(DocumentRepository documentRepository, DocumentChunkRepository documentChunkRepository) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
    }
}



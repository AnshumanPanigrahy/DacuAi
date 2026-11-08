package com.model.documindai.service;

import com.model.documindai.entity.Document;
import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.repository.DocumentChunkRepository;
import com.model.documindai.repository.DocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private PdfStorageService pdfStorageService;

    @Mock
    private dev.langchain4j.model.embedding.EmbeddingModel embeddingModel;

    @Mock
    private dev.langchain4j.store.embedding.EmbeddingStore<
            dev.langchain4j.data.segment.TextSegment> embeddingStore;

    @InjectMocks
    private DocumentService documentService;

    @Test
    void returnsChunksInRepositoryOrderWithPageNumbers() {
        Long documentId = 7L;
        Document document = new Document();
        DocumentChunk first = new DocumentChunk(1, "first", document);
        first.getPageNumbers().add(2);
        DocumentChunk second = new DocumentChunk(2, "second", document);
        second.getPageNumbers().add(4);
        List<DocumentChunk> chunks = List.of(first, second);

        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(document));
        when(documentChunkRepository
                .findByDocument_IdOrderByChunkNumberAsc(documentId))
                .thenReturn(chunks);

        List<DocumentChunk> result =
                documentService.getAllChunks(documentId);

        assertSame(chunks, result);
        assertEquals(1, result.get(0).getChunkNumber());
        assertEquals(List.of(2), result.get(0).getPageNumbers()
                .stream().toList());
        assertEquals(2, result.get(1).getChunkNumber());
        assertEquals(List.of(4), result.get(1).getPageNumbers()
                .stream().toList());
        verify(documentChunkRepository)
                .findByDocument_IdOrderByChunkNumberAsc(documentId);
    }

    @Test
    void returnsEmptyListForExistingDocumentWithoutChunks() {
        Long documentId = 8L;
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.of(new Document()));
        when(documentChunkRepository
                .findByDocument_IdOrderByChunkNumberAsc(documentId))
                .thenReturn(List.of());

        assertEquals(
                List.of(),
                documentService.getAllChunks(documentId)
        );
    }

    @Test
    void rejectsNonexistentDocument() {
        Long documentId = 99L;
        when(documentRepository.findById(documentId))
                .thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class,
                () -> documentService.getAllChunks(documentId)
        );
    }
}

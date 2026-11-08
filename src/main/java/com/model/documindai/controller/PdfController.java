package com.model.documindai.controller;

import com.model.documindai.model.DocumentResponse;
import com.model.documindai.model.ExtractedPage;
import com.model.documindai.model.PageAwareChunk;
import com.model.documindai.model.SearchResult;
import com.model.documindai.entity.Document;
import com.model.documindai.service.DocumentService;
import com.model.documindai.service.GeminiVisionOcrService;
import com.model.documindai.service.PdfOcrService;
import com.model.documindai.service.SearchService;
import com.model.documindai.service.TextChunkService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    private final TextChunkService textChunkService;
    private final SearchService searchService;
    private final DocumentService documentService;
    private final PdfOcrService pdfOcrService;
    private final GeminiVisionOcrService geminiVisionOcrService;

    public PdfController(
            TextChunkService textChunkService,
            SearchService searchService,
            DocumentService documentService,
            PdfOcrService pdfOcrService,
            GeminiVisionOcrService geminiVisionOcrService) {

        this.textChunkService = textChunkService;
        this.searchService = searchService;
        this.documentService = documentService;
        this.pdfOcrService = pdfOcrService;
        this.geminiVisionOcrService = geminiVisionOcrService;
    }

    @PostMapping("/upload")
    public List<String> uploadPdf(
            @RequestParam("file") MultipartFile file) {

        try {

            byte[] pdfBytes = file.getBytes();

            /*
             * First try normal PDF text extraction.
             */
            List<ExtractedPage> pages =
                    extractPages(pdfBytes);

            String text = pages.stream()
                    .map(ExtractedPage::text)
                    .filter(pageText ->
                            pageText != null && !pageText.isBlank())
                    .collect(Collectors.joining("\n"));

            List<String> chunks;
            List<PageAwareChunk> pageAwareChunks = null;

            if (text == null || text.trim().length() < 50) {

                System.out.println(
                        "Little or no text found. Starting Gemini document understanding..."
                );

                try {

                    text =
                            geminiVisionOcrService.extractText(
                                    pdfBytes
                            );

                    if (text == null || text.isBlank()) {
                        throw new RuntimeException(
                                "Gemini returned empty text."
                        );
                    }

                    chunks = textChunkService.splitText(
                            text,
                            500
                    );

                } catch (Exception e) {

                    e.printStackTrace();

                    List<ExtractedPage> ocrPages =
                            pdfOcrService.extractPages(pdfBytes);

                    pageAwareChunks = textChunkService.splitPages(
                            ocrPages,
                            500
                    );
                    chunks = pageAwareChunks
                            .stream()
                            .map(PageAwareChunk::text)
                            .toList();
                }

            } else {

                pageAwareChunks = textChunkService.splitPages(
                        pages,
                        500
                );
                chunks = pageAwareChunks
                        .stream()
                        .map(PageAwareChunk::text)
                        .toList();
            }

            if (chunks.isEmpty()) {

                throw new RuntimeException(
                        "No readable text could be extracted "
                                + "from the PDF."
                );
            }

            /*
             * Save document.
             */
            Document savedDocument =
                    documentService.saveDocumentWithPdf(
                            file.getOriginalFilename(),
                            pdfBytes
                    );

            /*
             * Save chunks + embeddings.
             */
            if (pageAwareChunks == null) {
                documentService.saveChunks(
                        savedDocument,
                        chunks
                );
            } else {
                documentService.savePageAwareChunks(
                        savedDocument,
                        pageAwareChunks
                );
            }

            return chunks;

        } catch (IOException e) {

            throw new RuntimeException(
                    "Failed to process PDF.",
                    e
            );
        }
    }

    private List<ExtractedPage> extractPages(byte[] pdfBytes)
            throws IOException {

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<ExtractedPage> pages = new ArrayList<>();

            for (int pageNumber = 1;
                 pageNumber <= document.getNumberOfPages();
                 pageNumber++) {
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                pages.add(new ExtractedPage(
                        pageNumber,
                        stripper.getText(document)
                ));
            }

            return pages;
        }
    }

    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String query) {

        return searchService.search(query);
    }

    @GetMapping("/documents")
    public List<DocumentResponse> getAllDocuments() {

        return documentService.getAllDocuments();
    }

    @GetMapping("/document/{id}")
    public DocumentResponse getDocumentById(
            @PathVariable Long id) {

        return documentService.getDocumentById(id);
    }

    @GetMapping(
            value = "/document/{id}/file",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<byte[]> getDocumentFile(
            @PathVariable Long id) {

        byte[] pdfBytes = documentService.getOriginalPdf(id);
        String safeFilename = "document-" + id + ".pdf";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(safeFilename)
                                .build()
                                .toString()
                )
                .body(pdfBytes);
    }

    @GetMapping("/statistics")
    public Map<String, Long> getStatistics() {

        return documentService.getStatistics();
    }

    @DeleteMapping("/document/{id}")
    public String deleteDocument(
            @PathVariable Long id) {

        documentService.deleteDocument(id);

        return "Document deleted successfully.";
    }
    @GetMapping("/search/document")
    public List<SearchResult> searchDocument(
            @RequestParam String query,
            @RequestParam Long documentId) {

        return searchService.searchByDocument(
                query,
                documentId
        );
    }
}
package com.model.documindai.controller;

import com.model.documindai.model.DocumentResponse;
import com.model.documindai.model.SearchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.model.documindai.entity.Document;
import com.model.documindai.service.DocumentService;
import com.model.documindai.service.PdfOcrService;
import com.model.documindai.service.SearchService;
import com.model.documindai.service.TextChunkService;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import com.model.documindai.service.GeminiVisionOcrService;

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

            String text;

            /*
             * First try normal PDF text extraction.
             */
            try (PDDocument document =
                         Loader.loadPDF(pdfBytes)) {

                PDFTextStripper stripper =
                        new PDFTextStripper();

                text = stripper.getText(document);
            }

            /*
             * If PDFBox could not extract enough text,
             * use OCR.
             */
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

                    System.out.println(
                            "Gemini document extraction successful."
                    );

                } catch (Exception e) {

                    e.printStackTrace();

                    text =
                            pdfOcrService.extractText(
                                    pdfBytes
                            );
                }

            } else {

                System.out.println(
                        "Text detected. Using normal PDF extraction."
                );
            }

            /*
             * Convert extracted/OCR text into chunks.
             */ 
            List<String> chunks =
                    textChunkService.splitText(
                            text,
                            500
                    );

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
                    documentService.saveDocument(
                            file.getOriginalFilename()
                    );

            /*
             * Save chunks + embeddings.
             */
            documentService.saveChunks(
                    savedDocument,
                    chunks
            );

            return chunks;

        } catch (IOException e) {

            throw new RuntimeException(
                    "Failed to process PDF.",
                    e
            );
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
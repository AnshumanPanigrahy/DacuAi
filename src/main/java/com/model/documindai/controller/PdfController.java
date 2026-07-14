package com.model.documindai.controller;

import com.model.documindai.service.SearchService;
import com.model.documindai.service.TextChunkService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/pdf")
public class PdfController {

    private final TextChunkService textChunkService;
    private final SearchService searchService;

    public PdfController(TextChunkService textChunkService,
                         SearchService searchService) {

        this.textChunkService = textChunkService;
        this.searchService = searchService;
    }

    @PostMapping("/upload")
    public List<String> uploadPdf(@RequestParam("file") MultipartFile file) {

        try {

            PDDocument document = Loader.loadPDF(file.getBytes());

            PDFTextStripper stripper = new PDFTextStripper();

            String text = stripper.getText(document);

            document.close();

            List<String> chunks =
                    textChunkService.splitText(text, 500);

            searchService.saveChunks(chunks);

            return chunks;

        } catch (IOException e) {

            throw new RuntimeException(e);
        }
    }

    @GetMapping("/search")
    public List<String> search(@RequestParam String keyword) {

        return searchService.semanticSearch(keyword);
    }
}
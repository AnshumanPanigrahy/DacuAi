package com.model.documindai.service;

import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.pdf.PdfFile;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.stereotype.Service;

import java.util.Base64;

@Service
public class GeminiVisionOcrService {

    private final ChatModel geminiModel;

    public GeminiVisionOcrService() {

        String apiKey = System.getenv("GEMINI_API_KEY_1");

        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No Gemini API key is configured."
            );
        }

        this.geminiModel =
                GoogleAiGeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName("gemini-3.6-flash")
                        .build();
    }

    public String extractText(byte[] pdfBytes) {

        String base64Pdf =
                Base64.getEncoder()
                        .encodeToString(pdfBytes);

        PdfFile pdfFile =
                PdfFile.builder()
                        .base64Data(base64Pdf)
                        .mimeType("application/pdf")
                        .build();

        UserMessage message =
                UserMessage.from(
                        TextContent.from("""
                                Extract all readable content from this PDF.

                                This document may contain handwritten text,
                                tables, headings, and diagrams.

                                IMPORTANT:
                                - Read the handwriting carefully.
                                - Preserve the original meaning.
                                - Do not summarize.
                                - Do not explain the document.
                                - Do not add information that is not present.
                                - Extract the text page by page.
                                - Preserve headings and important labels.
                                - For tables, represent the rows and columns
                                  as readable text.
                                - For diagrams, describe the labels and
                                  relationships briefly so they remain useful
                                  for semantic search.

                                Return only the extracted document content.
                                """),

                        PdfFileContent.from(pdfFile)
                );

        return geminiModel
                .chat(message)
                .aiMessage()
                .text();
    }
}
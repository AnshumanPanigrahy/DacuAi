package com.model.documindai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TextChunkService {

    public List<String> splitText(String text, int chunkSize) {

        List<String> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        // Clean PDF extraction artifacts
        String cleanedText = text
                .replace("\u0000", "")
                .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                .replaceAll("[ \t]+", " ")
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();

        // Split into paragraphs
        String[] paragraphs = cleanedText.split("\\n\\s*\\n");

        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {

            paragraph = paragraph.trim();

            if (paragraph.isEmpty()) {
                continue;
            }

            // If adding the paragraph stays within the target size
            if (currentChunk.length() + paragraph.length() + 1 <= chunkSize) {

                if (!currentChunk.isEmpty()) {
                    currentChunk.append("\n\n");
                }

                currentChunk.append(paragraph);

            } else {

                // Save current chunk
                if (!currentChunk.isEmpty()) {
                    chunks.add(currentChunk.toString().trim());
                }

                // If paragraph itself is larger than chunkSize,
                // split it using sentence boundaries.
                if (paragraph.length() > chunkSize) {

                    List<String> sentenceChunks =
                            splitLargeParagraph(paragraph, chunkSize);

                    chunks.addAll(sentenceChunks);

                    currentChunk.setLength(0);

                } else {

                    currentChunk.setLength(0);
                    currentChunk.append(paragraph);
                }
            }
        }

        // Add final chunk
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLargeParagraph(
            String paragraph,
            int chunkSize) {

        List<String> chunks = new ArrayList<>();

        String[] sentences =
                paragraph.split("(?<=[.!?])\\s+");

        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {

            sentence = sentence.trim();

            if (sentence.isEmpty()) {
                continue;
            }

            if (current.length() + sentence.length() + 1 <= chunkSize) {

                if (!current.isEmpty()) {
                    current.append(" ");
                }

                current.append(sentence);

            } else {

                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                }

                current.setLength(0);
                current.append(sentence);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }
}
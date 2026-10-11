package com.model.documindai.service;

import com.model.documindai.model.ExtractedPage;
import com.model.documindai.model.PageAwareChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    public List<PageAwareChunk> splitPages(
            List<ExtractedPage> pages,
            int chunkSize) {

        List<PageAwareChunk> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        Set<Integer> currentPageNumbers = new LinkedHashSet<>();

        for (ExtractedPage page : pages) {
            if (page.text() == null || page.text().isBlank()) {
                continue;
            }

            String cleanedText = page.text()
                    .replace("\u0000", "")
                    .replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "")
                    .replaceAll("[ \t]+", " ")
                    .replaceAll("\\r\\n", "\n")
                    .replaceAll("\\r", "\n")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();

            String[] paragraphs = cleanedText.split("\\n\\s*\\n");

            for (String paragraph : paragraphs) {
                paragraph = paragraph.trim();

                if (paragraph.isEmpty()) {
                    continue;
                }

                if (currentChunk.length() + paragraph.length() + 1 <= chunkSize) {
                    if (!currentChunk.isEmpty()) {
                        currentChunk.append("\n\n");
                    }

                    currentChunk.append(paragraph);
                    currentPageNumbers.add(page.pageNumber());
                } else {
                    if (!currentChunk.isEmpty()) {
                        chunks.add(new PageAwareChunk(
                                new ArrayList<>(currentPageNumbers),
                                currentChunk.toString().trim()
                        ));
                    }

                    if (paragraph.length() > chunkSize) {
                        for (String sentenceChunk :
                                splitLargeParagraph(paragraph, chunkSize)) {
                            chunks.add(new PageAwareChunk(
                                    List.of(page.pageNumber()),
                                    sentenceChunk
                            ));
                        }

                        currentChunk.setLength(0);
                        currentPageNumbers.clear();
                    } else {
                        currentChunk.setLength(0);
                        currentPageNumbers.clear();
                        currentChunk.append(paragraph);
                        currentPageNumbers.add(page.pageNumber());
                    }
                }
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(new PageAwareChunk(
                    new ArrayList<>(currentPageNumbers),
                    currentChunk.toString().trim()
            ));
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
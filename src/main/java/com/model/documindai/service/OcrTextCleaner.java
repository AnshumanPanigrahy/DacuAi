package com.model.documindai.service;

import org.springframework.stereotype.Service;

@Service
public class OcrTextCleaner {

    public String clean(String text) {

        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text;

        // Remove null characters
        cleaned = cleaned.replace("\u0000", "");

        // Remove control characters except newline and tab
        cleaned = cleaned.replaceAll(
                "[\\p{Cntrl}&&[^\r\n\t]]",
                ""
        );

        // Normalize Windows/Mac line endings
        cleaned = cleaned
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        // Remove trailing spaces from every line
        cleaned = cleaned.replaceAll(
                "[ \t]+(?=\n)",
                ""
        );

        // Remove excessive spaces/tabs
        cleaned = cleaned.replaceAll(
                "[ \t]{2,}",
                " "
        );

        // Remove lines containing only isolated punctuation
        cleaned = cleaned.replaceAll(
                "(?m)^\\s*[|_~`^.,:;]+\\s*$",
                ""
        );

        // Reduce excessive blank lines
        cleaned = cleaned.replaceAll(
                "\n{3,}",
                "\n\n"
        );

        return cleaned.trim();
    }
}
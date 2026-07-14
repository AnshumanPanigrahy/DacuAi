package com.model.documindai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TextChunkService {

    public List<String> splitText(String text, int chunkSize) {

        List<String> chunks = new ArrayList<>();

        for (int i = 0; i < text.length(); i += chunkSize) {

            chunks.add(
                    text.substring(
                            i,
                            Math.min(text.length(), i + chunkSize)
                    )
            );
        }

        return chunks;
    }
}
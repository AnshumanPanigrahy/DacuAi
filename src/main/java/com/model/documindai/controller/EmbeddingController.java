package com.model.documindai.controller;

import com.model.documindai.service.EmbeddingService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/embedding")
public class EmbeddingController {

    private final EmbeddingService embeddingService;


    public EmbeddingController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }


    @GetMapping("/test")
    public Map<String, Object> testEmbedding(
            @RequestParam String text) {

        float[] vector = embeddingService.generateEmbedding(text);

        Map<String, Object> response = new HashMap<>();

        response.put("text", text);
        response.put("dimensions", vector.length);

        return response;
    }
}
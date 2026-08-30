package com.model.documindai.controller;

import com.model.documindai.service.VectorStoreService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/vector")
public class VectorController {

    private final VectorStoreService vectorStoreService;

    public VectorController(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    @PostMapping("/store")
    public String store(@RequestParam String text) {
        return vectorStoreService.storeTestText(text);
    }
}
package com.model.documindai.controller;

import com.model.documindai.model.AskRequest;
import com.model.documindai.service.RagService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class AskController {

    private final RagService ragService;

    public AskController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/ask")
    public SseEmitter ask(@RequestBody AskRequest request) {

        SseEmitter emitter = new SseEmitter(120000L);

        ragService.streamAnswer(

                request.getQuestion(),

                // 1. Gemini answer tokens
                token -> {
                    try {
                        emitter.send(
                                SseEmitter.event()
                                        .name("token")
                                        .data(token)
                        );
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },

                // 2. Sources
                sources -> {
                    try {
                        emitter.send(
                                SseEmitter.event()
                                        .name("sources")
                                        .data(sources)
                        );
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },

                // 3. Error
                emitter::completeWithError,

                // 4. Complete
                emitter::complete
        );

        return emitter;
    }
}
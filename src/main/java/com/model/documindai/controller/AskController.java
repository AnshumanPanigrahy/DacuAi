package com.model.documindai.controller;

import com.model.documindai.model.AskRequest;
import com.model.documindai.model.WebSearchResponse;
import com.model.documindai.service.RagService;
import com.model.documindai.service.WebSearchService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class AskController {

    private final RagService ragService;
    private final WebSearchService webSearchService;
    private final long sseTimeoutMillis;

    public AskController(
            RagService ragService,
            WebSearchService webSearchService,
            @Value("${documind.summarization.maximum-duration-millis:120000}")
            long sseTimeoutMillis) {

        this.ragService = ragService;
        this.webSearchService = webSearchService;
        this.sseTimeoutMillis = sseTimeoutMillis;
    }

    @PostMapping("/ask")
    public SseEmitter ask(@RequestBody AskRequest request) {

        SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
        AtomicBoolean terminal = new AtomicBoolean(false);

        emitter.onCompletion(() -> terminal.set(true));
        emitter.onTimeout(() -> {
            if (terminal.compareAndSet(false, true)) {
                emitter.complete();
            }
        });
        emitter.onError(error -> terminal.set(true));

        ragService.streamAnswer(

                request.getQuestion(),
                request.getDocumentId(),

                // 1. Gemini answer tokens
                token -> {
                    if (terminal.get()) {
                        return;
                    }
                    try {
                        emitter.send(
                                SseEmitter.event()
                                        .name("token")
                                        .data(token)
                        );
                    } catch (Exception e) {
                        if (terminal.compareAndSet(false, true)) {
                            emitter.completeWithError(safeSseError());
                        }
                    }
                },

                // 2. Sources
                sources -> {
                    if (terminal.get()) {
                        return;
                    }
                    try {
                        emitter.send(
                                SseEmitter.event()
                                        .name("sources")
                                        .data(sources)
                        );
                    } catch (Exception e) {
                        if (terminal.compareAndSet(false, true)) {
                            emitter.completeWithError(safeSseError());
                        }
                    }
                },

                // 3. Error
                error -> {
                    if (terminal.compareAndSet(false, true)) {
                        emitter.completeWithError(safeSseError());
                    }
                },

                // 4. Complete
                () -> {
                    if (terminal.compareAndSet(false, true)) {
                        emitter.complete();
                    }
                },
                terminal::get
        );

        return emitter;
    }

    private IllegalStateException safeSseError() {
        return new IllegalStateException(
                "The request could not be completed."
        );
    }
    @GetMapping("/web-search")
    public WebSearchResponse webSearch(
            @RequestParam String query) {

        return webSearchService.search(query);
    }
}
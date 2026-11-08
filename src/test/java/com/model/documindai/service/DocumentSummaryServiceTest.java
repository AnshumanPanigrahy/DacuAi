package com.model.documindai.service;

import com.model.documindai.entity.DocumentChunk;
import com.model.documindai.model.SourceResult;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class DocumentSummaryServiceTest {

    @Test
    void summarizesMultipleBatchesAndPreservesAllSources() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        DocumentChunk first = chunk(1, 2, "a".repeat(700));
        DocumentChunk second = chunk(2, 4, "b".repeat(700));
        DocumentChunk third = chunk(3, 6, "c".repeat(700));

        when(documentService.getAllChunks(12L))
                .thenReturn(List.of(first, second, third));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenReturn("summary");

        DocumentSummaryService service =
                new DocumentSummaryService(
                        documentService,
                        geminiModelService,
                        2000
                );
        List<String> tokens = new ArrayList<>();
        List<List<SourceResult>> sources = new ArrayList<>();

        service.streamSummary(
                12L,
                tokens::add,
                sources::add,
                error -> {
                    throw new AssertionError(error);
                },
                () -> {
                }
        );

        assertEquals(1, tokens.size());
        assertEquals("summary", tokens.get(0));
        assertEquals(1, sources.size());
        assertEquals(List.of(1, 2, 3), sources.get(0).stream()
                .map(source -> Integer.parseInt(source.getChunkNumber()))
                .toList());
        assertEquals(List.of("1", "2", "3"), sources.get(0).stream()
                .map(SourceResult::getChunkId)
                .toList());
        assertEquals(List.of(2), sources.get(0).get(0).getPageNumbers());
        verify(documentService).getAllChunks(12L);
    }

    @Test
    void performsThreeReductionLevelsWhenNeeded() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        List<DocumentChunk> chunks = List.of(
                chunk(1, 1, "a".repeat(700)),
                chunk(2, 2, "b".repeat(700)),
                chunk(3, 3, "c".repeat(700)),
                chunk(4, 4, "d".repeat(700)),
                chunk(5, 5, "e".repeat(700))
        );
        AtomicInteger calls = new AtomicInteger();

        when(documentService.getAllChunks(13L)).thenReturn(chunks);
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenAnswer(invocation ->
                calls.getAndIncrement() < 5
                        ? "x".repeat(300)
                        : calls.get() < 9
                            ? "y".repeat(150)
                            : "reduced summary"
        );

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                13L,
                token -> {
                },
                sources -> {
                },
                error -> {
                    throw new AssertionError(error);
                },
                () -> {
                }
        );

        assertEquals(9, calls.get());
    }

    @Test
    void rejectsSummariesThatDoNotShrink() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        when(documentService.getAllChunks(17L)).thenReturn(List.of(
                chunk(1, 1, "a".repeat(700)),
                chunk(2, 2, "b".repeat(700)),
                chunk(3, 3, "c".repeat(700))
        ));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenReturn("x".repeat(700));
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                17L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains(
                "minimum progress ratio"
        ));
    }

    @Test
    void stopsAtConfiguredMaximumReductionDepth() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        when(documentService.getAllChunks(18L)).thenReturn(List.of(
                chunk(1, 1, "a".repeat(700)),
                chunk(2, 2, "b".repeat(700)),
                chunk(3, 3, "c".repeat(700))
        ));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenReturn("x".repeat(100));
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000,
                500,
                0,
                0.05
        ).streamSummary(
                18L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains(
                "maximum reduction depth"
        ));
    }

    @Test
    void reportsEmptyDocuments() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        when(documentService.getAllChunks(14L)).thenReturn(List.of());
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                14L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains("no readable chunks"));
    }

    @Test
    void reportsMissingDocuments() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        when(documentService.getAllChunks(15L)).thenThrow(
                new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "Document not found."
                )
        );
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                15L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0) instanceof ResponseStatusException);
    }

    @Test
    void reportsGeminiFailures() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(
                GeminiModelService.class
        );
        when(documentService.getAllChunks(16L))
                .thenReturn(List.of(chunk(1, 1, "content")));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("Gemini failed"));
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                16L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertEquals("Gemini failed", errors.get(0).getMessage());
    }

    @Test
    void enforcesPromptBudgetWithCompactSourceReferences() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(GeminiModelService.class);
        when(documentService.getAllChunks(19L)).thenReturn(List.of(
                chunk(1, 1, "a".repeat(5000)),
                chunk(2, 2, "b".repeat(5000))
        ));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenReturn("short summary");

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                19L,
                token -> {
                },
                sources -> {
                },
                error -> {
                    throw new AssertionError(error);
                },
                () -> {
                }
        );

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(geminiModelService, atLeastOnce()).generateText(
                prompts.capture(),
                anyLong()
        );
        assertTrue(prompts.getAllValues().stream()
                .allMatch(prompt -> prompt.length() <= 0
                        + 2000 - 0 + 10000));
        assertTrue(prompts.getAllValues().stream()
                .allMatch(prompt -> prompt.contains("[source S")));
    }

    @Test
    void stopsBeforeExceedingMaximumGeminiCalls() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(GeminiModelService.class);
        when(documentService.getAllChunks(20L)).thenReturn(List.of(
                chunk(1, 1, "a".repeat(700)),
                chunk(2, 2, "b".repeat(700)),
                chunk(3, 3, "c".repeat(700))
        ));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenReturn("x".repeat(100));
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000,
                500,
                8,
                0.05,
                120_000L,
                2
        ).streamSummary(
                20L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains(
                "maximum Gemini call count"
        ));
    }

    @Test
    void stopsWhenOverallDeadlineIsExceeded() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(GeminiModelService.class);
        when(documentService.getAllChunks(21L)).thenAnswer(invocation -> {
            Thread.sleep(5L);
            return List.of(chunk(1, 1, "content"));
        });
        List<Throwable> errors = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000,
                500,
                8,
                0.05,
                1L,
                10
        ).streamSummary(
                21L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                }
        );

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains(
                "overall deadline exceeded"
        ));
    }

    @Test
    void deduplicatesSourceMetadataWithoutChangingPayloadShape() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(GeminiModelService.class);
        DocumentChunk duplicate = chunk(1, 1, "content");
        when(documentService.getAllChunks(22L))
                .thenReturn(List.of(duplicate, duplicate));
        when(geminiModelService.generateText(anyString(), anyLong()))
                .thenReturn("summary");
        List<List<SourceResult>> sources = new ArrayList<>();

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                22L,
                token -> {
                },
                sources::add,
                error -> {
                    throw new AssertionError(error);
                },
                () -> {
                }
        );

        assertEquals(1, sources.get(0).size());
        assertEquals("1", sources.get(0).get(0).getChunkId());
    }

    @Test
    void stopsBeforeStartingGeminiWhenClientIsDisconnected() {
        DocumentService documentService = mock(DocumentService.class);
        GeminiModelService geminiModelService = mock(GeminiModelService.class);
        when(documentService.getAllChunks(23L))
                .thenReturn(List.of(chunk(1, 1, "content")));
        List<Throwable> errors = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(true);

        new DocumentSummaryService(
                documentService,
                geminiModelService,
                2000
        ).streamSummary(
                23L,
                token -> {
                },
                sources -> {
                },
                errors::add,
                () -> {
                },
                cancelled::get
        );

        assertEquals(1, errors.size());
        verify(geminiModelService, org.mockito.Mockito.never())
                .generateText(anyString(), anyLong());
    }

    private DocumentChunk chunk(
            int number,
            int page,
            String text) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId((long) number);
        chunk.setChunkNumber(number);
        chunk.setChunkText(text);
        chunk.getPageNumbers().add(page);
        return chunk;
    }
}

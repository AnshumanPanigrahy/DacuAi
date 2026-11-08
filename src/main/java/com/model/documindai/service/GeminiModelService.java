package com.model.documindai.service;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class GeminiModelService {

    private final List<String> apiKeys = new ArrayList<>();

    @Value("${documind.summarization.output-token-limit:500}")
    private int summarizationOutputTokenLimit;
    @Value("${documind.summarization.request-timeout-millis:120000}")
    private long summarizationRequestTimeoutMillis;
    @Value("${documind.summarization.maximum-retries-per-key:2}")
    private int maximumRetriesPerKey;
    @Value("${documind.summarization.retry-initial-delay-millis:500}")
    private long retryInitialDelayMillis;
    @Value("${documind.summarization.retry-maximum-delay-millis:5000}")
    private long retryMaximumDelayMillis;

    public GeminiModelService() {

        // Numbered keys are tried in ascending order. GEMINI_API_KEY is
        // retained as the final legacy fallback; blanks and duplicates
        // are ignored by addKey().
        addKey(System.getenv("GEMINI_API_KEY_1"));
        addKey(System.getenv("GEMINI_API_KEY_2"));
        addKey(System.getenv("GEMINI_API_KEY_3"));
        addKey(System.getenv("GEMINI_API_KEY_4"));
        addKey(System.getenv("GEMINI_API_KEY"));

        if (apiKeys.isEmpty()) {
            throw new IllegalStateException(
                    "No Gemini API key is configured."
            );
        }
    }

    private void addKey(String key) {

        if (key != null && !key.isBlank()) {

            if (!apiKeys.contains(key)) {
                apiKeys.add(key);
            }
        }
    }

    public List<StreamingChatModel> getModels() {

        List<StreamingChatModel> models =
                new ArrayList<>();

        for (String apiKey : apiKeys) {

            models.add(
                    GoogleAiGeminiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .modelName("gemini-3.6-flash")
                            .build()
            );
        }

        return models;
    }

    public String generateText(String prompt) {
        return generateText(prompt, Long.MAX_VALUE);
    }

    public String generateText(String prompt, long deadlineNanos) {
        return generateText(prompt, deadlineNanos, () -> {
        });
    }

    public String generateText(
            String prompt,
            long deadlineNanos,
            GeminiCallReservation reservation) {
        validateReliabilitySettings();
        Throwable lastError = null;

        List<StreamingChatModel> models = getSummaryModels();
        for (int modelIndex = 0; modelIndex < models.size(); modelIndex++) {
            StreamingChatModel model = models.get(modelIndex);
            int attempts = 0;
            while (true) {
                if (System.nanoTime() >= deadlineNanos) {
                    throw timeout("The summarization deadline was exceeded.");
                }

                // Reserve outside the retry/failure classifier so a rejected
                // budget reservation cannot be mistaken for a Gemini error.
                reservation.reserve();
                try {
                    return request(model, prompt, deadlineNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Gemini summarization was interrupted.",
                            e
                    );
                } catch (Exception error) {
                    lastError = unwrap(error);
                    FailureType failureType = classify(lastError);
                    if (failureType == FailureType.PERMANENT) {
                        throw new IllegalStateException(
                                "Gemini rejected the summarization request.",
                                lastError
                        );
                    }

                    boolean retrySameKey =
                            failureType == FailureType.TRANSIENT
                                    && attempts < maximumRetriesPerKey;
                    if (retrySameKey) {
                        try {
                            sleepBeforeRetry(attempts, deadlineNanos);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "Gemini summarization was interrupted.",
                                    interrupted
                            );
                        }
                        attempts++;
                        continue;
                    }
                    break;
                }
            }
        }

        throw new IllegalStateException(
                "Gemini summarization failed after bounded retries.",
                lastError
        );
    }

    private String request(
            StreamingChatModel model,
            String prompt,
            long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {

        if (deadlineNanos != Long.MAX_VALUE
                && System.nanoTime() >= deadlineNanos) {
            throw new TimeoutException("Summarization deadline exceeded.");
        }

        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();

        model.chat(
                prompt,
                new dev.langchain4j.model.chat.response
                        .StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String response) {
                        output.append(response);
                    }

                    @Override
                    public void onCompleteResponse(
                            dev.langchain4j.model.chat.response.ChatResponse
                                    response) {
                        if (response.finishReason() != null
                                && response.finishReason().name()
                                .equals("LENGTH")) {
                            result.completeExceptionally(
                                    new IllegalStateException(
                                            "Gemini summary output was truncated."
                                    )
                            );
                            return;
                        }
                        result.complete(output.toString());
                    }

                    @Override
                    public void onError(Throwable error) {
                        result.completeExceptionally(error);
                    }
                }
        );

        long remainingNanos = deadlineNanos == Long.MAX_VALUE
                ? TimeUnit.MILLISECONDS.toNanos(
                        summarizationRequestTimeoutMillis
                )
                : deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            result.cancel(true);
            throw new TimeoutException("Summarization deadline exceeded.");
        }

        try {
            String generated = result.get(
                    Math.min(
                            remainingNanos,
                            TimeUnit.MILLISECONDS.toNanos(
                                    summarizationRequestTimeoutMillis
                            )
                    ),
                    TimeUnit.NANOSECONDS
            );
            if (generated != null && !generated.isBlank()) {
                return generated.trim();
            }
            throw new IllegalStateException(
                    "Gemini returned an empty response."
            );
        } catch (TimeoutException error) {
            result.cancel(true);
            throw error;
        }
    }

    private void sleepBeforeRetry(int attempt, long deadlineNanos)
            throws InterruptedException {
        long exponentialDelay = retryInitialDelayMillis
                * (1L << Math.min(attempt, 30));
        long delay = Math.min(exponentialDelay, retryMaximumDelayMillis);
        if (deadlineNanos != Long.MAX_VALUE) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(
                    Math.max(0, deadlineNanos - System.nanoTime())
            );
            delay = Math.min(delay, remainingMillis);
        }
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    private FailureType classify(Throwable error) {
        String text = errorText(error);
        if (text.contains("429")
                || text.contains("RESOURCE_EXHAUSTED")
                || text.contains("RATE LIMIT")
                || text.contains("503")
                || text.contains("UNAVAILABLE")
                || text.contains("TIMEOUT")
                || text.contains("TIMED OUT")
                || text.contains("CONNECTION")
                || text.contains("SOCKET")
                || text.contains("EMPTY RESPONSE")
                || text.contains("TRUNCATED")) {
            return FailureType.TRANSIENT;
        }
        if (text.contains("401")
                || text.contains("403")
                || text.contains("UNAUTHENTICATED")
                || text.contains("PERMISSION")) {
            return FailureType.AUTHENTICATION;
        }
        return FailureType.PERMANENT;
    }

    private String errorText(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            text.append(' ')
                    .append(current.getClass().getSimpleName())
                    .append(' ')
                    .append(current.getMessage());
            current = current.getCause();
        }
        return text.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof ExecutionException
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private IllegalStateException timeout(String message) {
        return new IllegalStateException(message, new TimeoutException(message));
    }

    private void validateReliabilitySettings() {
        if (summarizationRequestTimeoutMillis < 1
                || maximumRetriesPerKey < 0
                || retryInitialDelayMillis < 0
                || retryMaximumDelayMillis < retryInitialDelayMillis) {
            throw new IllegalStateException(
                    "Invalid Gemini summarization reliability settings."
            );
        }
    }

    private enum FailureType {
        TRANSIENT,
        AUTHENTICATION,
        PERMANENT
    }

    private List<StreamingChatModel> getSummaryModels() {
        if (summarizationOutputTokenLimit < 1) {
            throw new IllegalStateException(
                    "Summarization output token limit must be positive."
            );
        }

        List<StreamingChatModel> models = new ArrayList<>();

        for (String apiKey : apiKeys) {
            models.add(
                    GoogleAiGeminiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .modelName("gemini-3.6-flash")
                            .maxOutputTokens(summarizationOutputTokenLimit)
                            .timeout(Duration.ofMillis(
                                    summarizationRequestTimeoutMillis
                            ))
                            .build()
            );
        }

        return models;
    }
}
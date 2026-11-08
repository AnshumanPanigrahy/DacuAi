package com.model.documindai.service;

import java.util.Locale;

/**
 * Supported summary intents are deliberately limited to explicit summary
 * requests. Focused questions continue through semantic retrieval.
 */
public final class SummaryIntentDetector {

    private SummaryIntentDetector() {
    }

    public static boolean isSummaryRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String normalized = question
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();

        return normalized.matches(
                "(summarize|summarise)( my| the| this)? document"
        ) || normalized.matches(
                "(give|provide|write) (me )?(a )?summary( of| for| on)?"
                        + "( my| the| this)? document?"
        ) || normalized.matches(
                "(tell me about|give me an overview of)"
                        + " (my|the|this) document"
        ) || normalized.equals("document overview")
                || normalized.equals("summary");
    }
}

package com.model.documindai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryIntentDetectorTest {

    @Test
    void recognizesSupportedSummaryPatterns() {
        assertTrue(SummaryIntentDetector.isSummaryRequest(
                "Summarize my document"
        ));
        assertTrue(SummaryIntentDetector.isSummaryRequest(
                "Tell me about this document"
        ));
        assertTrue(SummaryIntentDetector.isSummaryRequest(
                "give me a summary of the document"
        ));
    }

    @Test
    void leavesFocusedQuestionsOnSemanticPath() {
        assertFalse(SummaryIntentDetector.isSummaryRequest(
                "Explain Artificial Intelligence"
        ));
        assertFalse(SummaryIntentDetector.isSummaryRequest(
                "What date was the project created?"
        ));
    }
}

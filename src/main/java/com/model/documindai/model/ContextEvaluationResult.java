package com.model.documindai.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ContextEvaluationResult {

    private boolean sufficient;

    private double score;

    private String reason;
}
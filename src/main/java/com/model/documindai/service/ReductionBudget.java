package com.model.documindai.service;

import org.springframework.stereotype.Component;

@Component
public class ReductionBudget {

    public static final int SOURCE_REFERENCE_OVERHEAD = 32;

    public int availableContextBudget(
            int inputCharacterBudget,
            int outputCharacterAllowance,
            int fixedPromptLength) {

        if (inputCharacterBudget < 1
                || outputCharacterAllowance < 0
                || fixedPromptLength < 0) {
            throw new IllegalArgumentException(
                    "Character budget values must be non-negative."
            );
        }

        return Math.max(
                1,
                inputCharacterBudget
                        - outputCharacterAllowance
                        - fixedPromptLength
        );
    }

    public int contentBudget(int availableContextBudget) {
        return Math.max(
                1,
                availableContextBudget - SOURCE_REFERENCE_OVERHEAD
        );
    }
}

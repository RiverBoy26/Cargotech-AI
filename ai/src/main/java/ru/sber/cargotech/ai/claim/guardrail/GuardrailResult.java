package ru.sber.cargotech.ai.claim.guardrail;

import java.util.List;

public record GuardrailResult(
        GuardrailDecision decision,
        List<String> errors,
        List<String> warnings
) {
    public boolean passed() {
        return decision != GuardrailDecision.BLOCK;
    }

    public boolean blocked() {
        return decision == GuardrailDecision.BLOCK;
    }

    public boolean reviewRequired() {
        return decision == GuardrailDecision.REVIEW || decision == GuardrailDecision.BLOCK;
    }
}
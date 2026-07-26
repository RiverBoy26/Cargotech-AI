package ru.sber.cargotech.ai.llm.logging;

import java.time.Instant;

public record LlmCallLog(
        String requestId,
        String caseId,
        String operation,
        String provider,
        String model,
        String promptPreview,
        String rawResponsePreview,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        LlmCallStatus status,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs
) {
}
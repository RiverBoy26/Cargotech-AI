package ru.sber.cargotech.ai.llm.logging;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LlmCallLog(
        String requestId,
        String caseId,
        UUID userId,
        String operation,
        String provider,
        String model,
        String promptVersion,
        @JsonIgnore String rawPrompt,
        String maskedPrompt,
        @JsonIgnore String rawResponse,
        String maskedResponse,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal costRub,
        LlmCallStatus status,
        String errorMessage,
        boolean providerInvoked,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs
) {
}

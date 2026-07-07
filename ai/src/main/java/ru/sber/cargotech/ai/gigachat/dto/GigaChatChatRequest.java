package ru.sber.cargotech.ai.gigachat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GigaChatChatRequest(
        String model,
        List<GigaChatMessage> messages,
        Double temperature,

        @JsonProperty("max_tokens")
        Integer maxTokens
) {
}
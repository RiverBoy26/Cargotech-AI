package ru.sber.cargotech.ai.gigachat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GigaChatEmbeddingsResponse(
        List<EmbeddingData> data,
        String model,
        Usage usage
) {
    public record EmbeddingData(
            Integer index,
            List<Double> embedding
    ) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens")
            Integer promptTokens,

            @JsonProperty("total_tokens")
            Integer totalTokens
    ) {
    }

    public List<Double> firstEmbedding() {
        if (data == null || data.isEmpty()) {
            return List.of();
        }

        EmbeddingData first = data.get(0);

        if (first == null || first.embedding() == null) {
            return List.of();
        }

        return first.embedding();
    }
}
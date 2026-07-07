package ru.sber.cargotech.ai.gigachat.dto;

import java.util.List;

public record GigaChatEmbeddingsRequest(
        String model,
        List<String> input
) {
}
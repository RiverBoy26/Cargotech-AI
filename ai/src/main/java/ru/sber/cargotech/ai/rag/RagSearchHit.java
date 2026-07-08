package ru.sber.cargotech.ai.rag;

public record RagSearchHit(
        String pointId,
        Double score,
        RagChunk chunk
) {
}
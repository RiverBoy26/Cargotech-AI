package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record SearchRagChunksRequest(
        String query,

        Map<String, Object> filters,

        Integer limit,

        @JsonProperty("min_score")
        Double minScore
) {
}
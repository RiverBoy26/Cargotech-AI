package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.rag.RagSearchHit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SearchRagChunksResponse(
        Boolean success,

        String query,

        Map<String, Object> filters,

        Integer limit,

        @JsonProperty("min_score")
        Double minScore,

        @JsonProperty("hits_count")
        Integer hitsCount,

        List<RagSearchHit> hits,

        List<String> warnings,

        @JsonProperty("checked_at")
        Instant checkedAt
) {
}
package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record IndexRagChunksResponse(
        Boolean success,

        @JsonProperty("source_batch_id")
        String sourceBatchId,

        @JsonProperty("indexed_chunks")
        Integer indexedChunks,

        @JsonProperty("chunk_ids")
        List<String> chunkIds,

        @JsonProperty("qdrant_response")
        Object qdrantResponse,

        @JsonProperty("checked_at")
        Instant checkedAt
) {
}
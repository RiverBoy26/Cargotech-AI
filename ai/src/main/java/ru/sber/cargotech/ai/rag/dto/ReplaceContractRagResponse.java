package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record ReplaceContractRagResponse(
    boolean success,
    @JsonProperty("organization_id") String organizationId,
    @JsonProperty("client_id") String clientId,
    @JsonProperty("contract_id") String contractId,
    @JsonProperty("indexed_chunks") int indexedChunks,
    @JsonProperty("chunk_ids") List<String> chunkIds,
    @JsonProperty("delete_response") Object deleteResponse,
    @JsonProperty("index_response") Object indexResponse,
    @JsonProperty("completed_at") Instant completedAt
) {}

package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ReplaceContractRagRequest(
    @JsonProperty("organization_id") String organizationId,
    @JsonProperty("client_id") String clientId,
    @JsonProperty("contract_id") String contractId,
    @JsonProperty("source_batch_id") String sourceBatchId,
    @JsonProperty("source_system") String sourceSystem,
    List<IndexRagChunksRequest.IndexRagChunk> chunks
) {}

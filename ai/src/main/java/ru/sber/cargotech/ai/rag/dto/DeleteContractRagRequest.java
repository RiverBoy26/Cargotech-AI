package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteContractRagRequest(
    @JsonProperty("organization_id") String organizationId,
    @JsonProperty("client_id") String clientId,
    @JsonProperty("contract_id") String contractId,
    @JsonProperty("source_id") String sourceId
) {}

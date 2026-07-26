package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PaymentDelayRagContextRequest(
        @JsonProperty("contract_id")
        String contractId,

        @JsonProperty("client_id")
        String clientId
) {
}
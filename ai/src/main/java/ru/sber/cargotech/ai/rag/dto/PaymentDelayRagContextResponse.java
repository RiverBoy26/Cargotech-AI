package ru.sber.cargotech.ai.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.rag.RagSearchService;

import java.time.Instant;

public record PaymentDelayRagContextResponse(
        Boolean success,

        @JsonProperty("contract_id")
        String contractId,

        @JsonProperty("client_id")
        String clientId,

        @JsonProperty("rag_context")
        RagSearchService.PaymentDelayRagContext ragContext,

        @JsonProperty("checked_at")
        Instant checkedAt
) {
}
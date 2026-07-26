package ru.sber.cargotech.ai.claim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.time.Instant;
import java.util.List;

public record GenerateClaimPipelineResponse(
        Boolean success,

        String status,

        @JsonProperty("claim_type")
        GenerateClaimRequest.ClaimType claimType,

        @JsonProperty("rag_used")
        Boolean ragUsed,

        @JsonProperty("rag_warnings")
        List<String> ragWarnings,

        @JsonProperty("prompt_messages")
        List<GigaChatMessage> promptMessages,

        @JsonProperty("raw_model_response")
        String rawModelResponse,

        @JsonProperty("generated_claim")
        GenerateClaimResponse generatedClaim,

        @JsonProperty("guardrail_result")
        GuardrailResult guardrailResult,

        @JsonProperty("checked_at")
        Instant checkedAt
) {
}

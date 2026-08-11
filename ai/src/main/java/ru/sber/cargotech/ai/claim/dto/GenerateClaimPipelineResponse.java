package ru.sber.cargotech.ai.claim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.rag.RagSearchService;

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

        @JsonProperty("request_id")
        String requestId,

        @JsonProperty("token_usage")
        GigaChatChatResponse.Usage tokenUsage,

        @JsonProperty("generated_claim")
        GenerateClaimResponse generatedClaim,

        @JsonProperty("guardrail_result")
        GuardrailResult guardrailResult,

        @JsonProperty("retrieved_fragments")
        List<RagSearchService.RetrievedFragment> retrievedFragments,

        @JsonProperty("checked_at")
        Instant checkedAt
) {
}

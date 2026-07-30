package ru.sber.cargotech.ai.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;

import java.time.Instant;
import java.util.List;

public record GenerateDocumentPipelineResponse(
        Boolean success,
        String status,

        @JsonProperty("document_type")
        GenerateClaimResponse.DocumentType documentType,

        @JsonProperty("rag_used")
        Boolean ragUsed,

        @JsonProperty("rag_warnings")
        List<String> ragWarnings,

        @JsonProperty("request_id")
        String requestId,

        @JsonProperty("token_usage")
        GigaChatChatResponse.Usage tokenUsage,

        @JsonProperty("generated_document")
        GenerateDocumentResponse generatedDocument,

        @JsonProperty("guardrail_result")
        GuardrailResult guardrailResult,

        @JsonProperty("checked_at")
        Instant checkedAt
) {
}

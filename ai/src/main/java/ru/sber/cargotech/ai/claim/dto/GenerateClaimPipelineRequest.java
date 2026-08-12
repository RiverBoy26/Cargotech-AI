package ru.sber.cargotech.ai.claim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record GenerateClaimPipelineRequest(
        @JsonProperty("case_facts")
        GenerateClaimRequest.CaseFacts caseFacts,

        @JsonProperty("backend_calculation")
        GenerateClaimRequest.BackendCalculation backendCalculation,

        @JsonProperty("contract_context")
        List<GenerateClaimRequest.ContractContextChunk> contractContext,

        @JsonProperty("legal_context")
        List<GenerateClaimRequest.LegalContextItem> legalContext,

        @JsonProperty("template_context")
        GenerateClaimRequest.TemplateContext templateContext,

        @JsonProperty("similar_examples")
        List<GenerateClaimRequest.SimilarExample> similarExamples,

        @JsonProperty("rag_options")
        RagOptions ragOptions
) {
    public record RagOptions(
            Boolean enabled,

            @JsonProperty("contract_id")
            String contractId,

            @JsonProperty("client_id")
            String clientId,

            @JsonProperty("organization_id")
            String organizationId
    ) {
        public RagOptions(Boolean enabled, String contractId, String clientId) {
            this(enabled, contractId, clientId, null);
        }
    }

    public GenerateClaimRequest toGenerateClaimRequest() {
        return new GenerateClaimRequest(
                caseFacts,
                backendCalculation,
                contractContext,
                legalContext,
                templateContext,
                similarExamples
        );
    }
}

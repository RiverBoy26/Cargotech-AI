package ru.sber.cargotech.claim.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiGenerateClaimResponse(
        Boolean success,
        String status,
        @JsonProperty("rag_warnings") List<String> ragWarnings,
        @JsonProperty("generated_claim") GeneratedClaim generatedClaim,
        @JsonProperty("guardrail_result") GuardrailResult guardrailResult,
        @JsonProperty("retrieved_fragments") List<RetrievedFragment> retrievedFragments
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratedClaim(
            @JsonProperty("claim_text") String claimText,
            @JsonProperty("summary_for_lawyer") String summaryForLawyer,
            List<String> warnings,
            @JsonProperty("manual_review_required") Boolean manualReviewRequired
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuardrailResult(String decision, List<String> errors, List<String> warnings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetrievedFragment(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("chunk_id") String chunkId,
            Double score,
            String text
    ) {}
}

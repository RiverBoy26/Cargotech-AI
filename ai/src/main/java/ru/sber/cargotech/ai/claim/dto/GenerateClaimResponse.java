package ru.sber.cargotech.ai.claim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record GenerateClaimResponse(
        @JsonProperty("claim_type")
        GenerateClaimRequest.ClaimType claimType,

        @JsonProperty("claim_text")
        String claimText,

        @JsonProperty("summary_for_lawyer")
        String summaryForLawyer,

        @JsonProperty("used_contract_clauses")
        List<UsedContractClause> usedContractClauses,

        @JsonProperty("used_law_articles")
        List<UsedLawArticle> usedLawArticles,

        @JsonProperty("backend_calculation_used")
        BackendCalculationUsed backendCalculationUsed,

        List<Attachment> attachments,

        List<String> warnings,

        @JsonProperty("manual_review_required")
        Boolean manualReviewRequired
) {
    public record UsedContractClause(
            @JsonProperty("clause_number")
            String clauseNumber,

            @JsonProperty("chunk_id")
            String chunkId,

            String reason
    ) {
    }

    public record UsedLawArticle(
            @JsonProperty("law_code")
            String lawCode,

            String article,
            String reason
    ) {
    }

    public record BackendCalculationUsed(
            @JsonProperty("principal_debt")
            BigDecimal principalDebt,

            @JsonProperty("penalty_type")
            GenerateClaimRequest.PenaltyType penaltyType,

            @JsonProperty("penalty_amount")
            BigDecimal penaltyAmount,

            @JsonProperty("total_amount")
            BigDecimal totalAmount,

            @JsonProperty("overdue_days")
            Integer overdueDays,

            String currency
    ) {
    }

    public record Attachment(
            @JsonProperty("document_type")
            DocumentType documentType,

            @JsonProperty("document_name")
            String documentName,

            Boolean required
    ) {
    }

    public enum DocumentType {
        CONTRACT,
        ACT,
        TTN,
        INVOICE,
        CALCULATION,
        PAYMENT_EXTRACT,
        OTHER
    }
}
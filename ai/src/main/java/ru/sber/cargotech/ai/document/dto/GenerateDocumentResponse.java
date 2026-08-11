package ru.sber.cargotech.ai.document.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;

import java.util.List;

public record GenerateDocumentResponse(
        @JsonProperty("document_type")
        GenerateClaimResponse.DocumentType documentType,

        @JsonProperty("document_title")
        String documentTitle,

        @JsonProperty("document_text")
        String documentText,

        @JsonProperty("summary_for_lawyer")
        String summaryForLawyer,

        @JsonProperty("used_contract_clauses")
        List<GenerateClaimResponse.UsedContractClause> usedContractClauses,

        @JsonProperty("used_law_articles")
        List<GenerateClaimResponse.UsedLawArticle> usedLawArticles,

        List<GenerateClaimResponse.Attachment> attachments,

        List<String> warnings,

        @JsonProperty("manual_review_required")
        Boolean manualReviewRequired
) {
}

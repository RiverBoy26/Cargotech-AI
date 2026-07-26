package ru.sber.cargotech.ai.claim.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public record GenerateClaimRequest(
        @JsonProperty("case_facts")
        CaseFacts caseFacts,

        @JsonProperty("backend_calculation")
        BackendCalculation backendCalculation,

        @JsonProperty("contract_context")
        List<ContractContextChunk> contractContext,

        @JsonProperty("legal_context")
        List<LegalContextItem> legalContext,

        @JsonProperty("template_context")
        TemplateContext templateContext,

        @JsonProperty("similar_examples")
        List<SimilarExample> similarExamples
) {
    public record CaseFacts(
            @JsonProperty("claim_id")
            String claimId,

            @JsonProperty("claim_type")
            ClaimType claimType,

            Party creditor,
            Party debtor,
            ContractFacts contract,
            ShipmentFacts shipment,
            PaymentFacts payment,

            @JsonProperty("claim_date")
            String claimDate
    ) {
    }

    public enum ClaimType {
        PAYMENT_DELAY,
        LOADING_FAILURE
    }

    public record Party(
            String name,
            String inn,

            @JsonProperty("legal_address")
            String legalAddress
    ) {
    }

    public record ContractFacts(
            @JsonProperty("contract_number")
            String contractNumber,

            @JsonProperty("contract_date")
            String contractDate
    ) {
    }

    public record ShipmentFacts(
            @JsonProperty("order_number")
            String orderNumber,

            String route,

            @JsonProperty("act_number")
            String actNumber,

            @JsonProperty("act_date")
            String actDate,

            @JsonProperty("ttn_number")
            String ttnNumber,

            @JsonProperty("invoice_number")
            String invoiceNumber,

            @JsonProperty("loading_date")
            String loadingDate,

            @JsonProperty("loading_address")
            String loadingAddress,

            @JsonProperty("loading_time_window")
            String loadingTimeWindow,

            @JsonProperty("vehicle_requirements")
            String vehicleRequirements,

            @JsonProperty("carrier_name")
            String carrierName,

            @JsonProperty("failure_confirmed_by_dispatcher")
            Boolean failureConfirmedByDispatcher
    ) {
        public ShipmentFacts(
                String orderNumber,
                String route,
                String actNumber,
                String actDate,
                String ttnNumber,
                String invoiceNumber
        ) {
            this(
                    orderNumber,
                    route,
                    actNumber,
                    actDate,
                    ttnNumber,
                    invoiceNumber,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }

    public record PaymentFacts(
            @JsonProperty("payment_due_date")
            String paymentDueDate,

            @JsonProperty("payment_status")
            PaymentStatus paymentStatus,

            @JsonProperty("payment_confirmed_by_accountant")
            Boolean paymentConfirmedByAccountant
    ) {
    }

    public enum PaymentStatus {
        PAID,
        UNPAID,
        PARTIALLY_PAID,
        UNKNOWN
    }

    public record BackendCalculation(
            @JsonProperty("principal_debt")
            BigDecimal principalDebt,

            @JsonProperty("penalty_type")
            PenaltyType penaltyType,

            @JsonProperty("penalty_rate_text")
            String penaltyRateText,

            @JsonProperty("overdue_days")
            Integer overdueDays,

            @JsonProperty("penalty_amount")
            BigDecimal penaltyAmount,

            @JsonProperty("total_amount")
            BigDecimal totalAmount,

            String currency,

            @JsonProperty("formula_text")
            String formulaText
    ) {
    }

    public enum PenaltyType {
        CONTRACT_PENALTY,
        LEGAL_INTEREST,
        NONE
    }

    public record ContractContextChunk(
            @JsonProperty("chunk_id")
            String chunkId,

            @JsonProperty("clause_number")
            String clauseNumber,

            @JsonProperty("section_title")
            String sectionTitle,

            String text
    ) {
    }

    public record LegalContextItem(
            @JsonProperty("law_code")
            String lawCode,

            String article,
            String purpose
    ) {
    }

    public record TemplateContext(
            @JsonProperty("template_id")
            String templateId,

            @JsonProperty("template_name")
            String templateName,

            @JsonProperty("template_type")
            ClaimType templateType,

            @JsonProperty("template_structure")
            List<String> templateStructure
    ) {
    }

    public record SimilarExample(
            @JsonProperty("example_id")
            String exampleId,

            @JsonProperty("claim_type")
            ClaimType claimType,

            @JsonProperty("usage_rule")
            String usageRule,

            @JsonProperty("structure_summary")
            String structureSummary
    ) {
    }
}
package ru.sber.cargotech.ai.claim.guardrail;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;

import java.math.BigDecimal;
import java.util.*;

@Service
public class RuleBasedGuardrailService {

    public GuardrailResult check(GenerateClaimRequest request, GenerateClaimResponse response) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateRequest(request, errors, warnings);
        validateResponse(response, errors, warnings);

        if (request != null && response != null) {
            validateClaimType(request, response, errors);
            validateCalculationNotChanged(request, response, errors);
            validateUsedContractClauses(request, response, errors, warnings);
            validateUsedLawArticles(request, response, errors, warnings);
            validateForbiddenText(response, errors);
            validateManualReview(response, errors);
        }

        GuardrailDecision decision;

        if (!errors.isEmpty()) {
            decision = GuardrailDecision.BLOCK;
        } else if (!warnings.isEmpty()) {
            decision = GuardrailDecision.REVIEW;
        } else {
            decision = GuardrailDecision.PASS;
        }

        return new GuardrailResult(
                decision,
                List.copyOf(errors),
                List.copyOf(warnings)
        );
    }

    private void validateRequest(GenerateClaimRequest request, List<String> errors, List<String> warnings) {
        if (request == null) {
            errors.add("Request is null");
            return;
        }

        if (request.caseFacts() == null) {
            errors.add("case_facts is required");
            return;
        }

        GenerateClaimRequest.ClaimType claimType = request.caseFacts().claimType();

        if (claimType == null) {
            errors.add("case_facts.claim_type is required");
            return;
        }

        if (request.caseFacts().creditor() == null) {
            errors.add("case_facts.creditor is required");
        }

        if (request.caseFacts().debtor() == null) {
            errors.add("case_facts.debtor is required");
        }

        if (claimType == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            validatePaymentDelayRequest(request, errors);
        } else if (claimType == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            validateLoadingFailureRequest(request, errors, warnings);
        } else {
            errors.add("Unsupported claim_type: " + claimType);
        }

        validateBackendCalculation(request, errors, claimType);

        if (request.contractContext() == null || request.contractContext().isEmpty()) {
            warnings.add("contract_context is empty");
        }

        if (request.legalContext() == null || request.legalContext().isEmpty()) {
            warnings.add("legal_context is empty");
        }

        if (request.templateContext() == null) {
            warnings.add("template_context is empty");
        }
    }

    private void validatePaymentDelayRequest(GenerateClaimRequest request, List<String> errors) {
        if (request.caseFacts().payment() == null) {
            errors.add("case_facts.payment is required for PAYMENT_DELAY");
            return;
        }

        if (request.caseFacts().payment().paymentStatus() != GenerateClaimRequest.PaymentStatus.UNPAID) {
            errors.add("payment_status must be UNPAID for PAYMENT_DELAY claim");
        }

        if (!Boolean.TRUE.equals(request.caseFacts().payment().paymentConfirmedByAccountant())) {
            errors.add("payment_confirmed_by_accountant must be true");
        }
    }

    private void validateLoadingFailureRequest(
            GenerateClaimRequest request,
            List<String> errors,
            List<String> warnings
    ) {
        if (request.caseFacts().shipment() == null) {
            errors.add("case_facts.shipment is required for LOADING_FAILURE");
            return;
        }

        GenerateClaimRequest.ShipmentFacts shipment = request.caseFacts().shipment();

        if (isBlank(shipment.orderNumber())) {
            warnings.add("case_facts.shipment.order_number is empty for LOADING_FAILURE");
        }

        if (isBlank(shipment.loadingDate())) {
            warnings.add("case_facts.shipment.loading_date is empty for LOADING_FAILURE");
        }

        if (isBlank(shipment.loadingAddress())) {
            warnings.add("case_facts.shipment.loading_address is empty for LOADING_FAILURE");
        }

        if (!Boolean.TRUE.equals(shipment.failureConfirmedByDispatcher())) {
            warnings.add("loading failure is not confirmed by dispatcher");
        }
    }

    private void validateBackendCalculation(
            GenerateClaimRequest request,
            List<String> errors,
            GenerateClaimRequest.ClaimType claimType
    ) {
        if (request.backendCalculation() == null) {
            errors.add("backend_calculation is required");
            return;
        }

        GenerateClaimRequest.BackendCalculation calculation = request.backendCalculation();

        if (calculation.penaltyType() == null) {
            errors.add("backend_calculation.penalty_type is required");
        }

        if (isBlank(calculation.currency())) {
            errors.add("backend_calculation.currency is required");
        }

        if (calculation.totalAmount() == null || calculation.totalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("backend_calculation.total_amount must be positive");
        }

        if (calculation.penaltyAmount() == null || calculation.penaltyAmount().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("backend_calculation.penalty_amount must be zero or positive");
        }

        if (calculation.overdueDays() != null && calculation.overdueDays() < 0) {
            errors.add("backend_calculation.overdue_days must be zero or positive");
        }

        if (claimType == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            if (calculation.principalDebt() == null || calculation.principalDebt().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("backend_calculation.principal_debt must be positive for PAYMENT_DELAY");
            }

            if (calculation.overdueDays() == null) {
                errors.add("backend_calculation.overdue_days is required for PAYMENT_DELAY");
            }
        }

        if (claimType == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            if (calculation.penaltyType() == GenerateClaimRequest.PenaltyType.NONE) {
                errors.add("backend_calculation.penalty_type must not be NONE for LOADING_FAILURE");
            }

            if (calculation.penaltyAmount() == null || calculation.penaltyAmount().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("backend_calculation.penalty_amount must be positive for LOADING_FAILURE");
            }
        }
    }

    private void validateResponse(GenerateClaimResponse response, List<String> errors, List<String> warnings) {
        if (response == null) {
            errors.add("Response is null");
            return;
        }

        if (response.claimType() == null) {
            errors.add("response.claim_type is required");
        }

        if (isBlank(response.claimText())) {
            errors.add("response.claim_text is required");
        }

        if (isBlank(response.summaryForLawyer())) {
            errors.add("response.summary_for_lawyer is required");
        }

        if (response.backendCalculationUsed() == null) {
            errors.add("response.backend_calculation_used is required");
        }

        if (response.usedContractClauses() == null) {
            warnings.add("response.used_contract_clauses is null");
        }

        if (response.usedLawArticles() == null) {
            warnings.add("response.used_law_articles is null");
        }

        if (response.attachments() == null) {
            warnings.add("response.attachments is null");
        }

        if (response.warnings() == null) {
            warnings.add("response.warnings is null");
        }
    }

    private void validateClaimType(GenerateClaimRequest request, GenerateClaimResponse response, List<String> errors) {
        if (request.caseFacts() == null || response.claimType() == null) {
            return;
        }

        if (request.caseFacts().claimType() != response.claimType()) {
            errors.add("Model changed claim_type");
        }
    }

    private void validateCalculationNotChanged(GenerateClaimRequest request, GenerateClaimResponse response, List<String> errors) {
        if (request.backendCalculation() == null || response.backendCalculationUsed() == null) {
            return;
        }

        GenerateClaimRequest.BackendCalculation expected = request.backendCalculation();
        GenerateClaimResponse.BackendCalculationUsed actual = response.backendCalculationUsed();

        if (!sameBigDecimal(expected.principalDebt(), actual.principalDebt())) {
            errors.add("Model changed principal_debt");
        }

        if (expected.penaltyType() != actual.penaltyType()) {
            errors.add("Model changed penalty_type");
        }

        if (!sameBigDecimal(expected.penaltyAmount(), actual.penaltyAmount())) {
            errors.add("Model changed penalty_amount");
        }

        if (!sameBigDecimal(expected.totalAmount(), actual.totalAmount())) {
            errors.add("Model changed total_amount");
        }

        if (!Objects.equals(expected.overdueDays(), actual.overdueDays())) {
            errors.add("Model changed overdue_days");
        }

        if (!sameText(expected.currency(), actual.currency())) {
            errors.add("Model changed currency");
        }
    }

    private void validateUsedContractClauses(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        Set<String> allowedChunkIds = new HashSet<>();
        Set<String> allowedClauseNumbers = new HashSet<>();

        for (GenerateClaimRequest.ContractContextChunk chunk : safeList(request.contractContext())) {
            if (!isBlank(chunk.chunkId())) {
                allowedChunkIds.add(chunk.chunkId());
            }

            if (!isBlank(chunk.clauseNumber())) {
                allowedClauseNumbers.add(chunk.clauseNumber());
            }
        }

        List<GenerateClaimResponse.UsedContractClause> usedClauses = safeList(response.usedContractClauses());

        if (usedClauses.isEmpty()) {
            warnings.add("Model did not cite contract clauses");
            return;
        }

        for (GenerateClaimResponse.UsedContractClause used : usedClauses) {
            if (!isBlank(used.chunkId()) && !allowedChunkIds.contains(used.chunkId())) {
                errors.add("Model used unknown contract chunk_id: " + used.chunkId());
            }

            if (!isBlank(used.clauseNumber()) && !allowedClauseNumbers.contains(used.clauseNumber())) {
                errors.add("Model used unknown contract clause_number: " + used.clauseNumber());
            }
        }
    }

    private void validateUsedLawArticles(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        Set<String> allowedArticles = new HashSet<>();

        for (GenerateClaimRequest.LegalContextItem item : safeList(request.legalContext())) {
            if (!isBlank(item.lawCode()) && !isBlank(item.article())) {
                allowedArticles.add(normalizeKey(item.lawCode(), item.article()));
            }
        }

        List<GenerateClaimResponse.UsedLawArticle> usedArticles = safeList(response.usedLawArticles());

        if (usedArticles.isEmpty()) {
            warnings.add("Model did not cite law articles");
            return;
        }

        for (GenerateClaimResponse.UsedLawArticle used : usedArticles) {
            String key = normalizeKey(used.lawCode(), used.article());

            if (!allowedArticles.contains(key)) {
                errors.add("Model used law article not present in legal_context: " + used.lawCode() + " " + used.article());
            }
        }
    }

    private void validateForbiddenText(GenerateClaimResponse response, List<String> errors) {
        String text = response.claimText() == null ? "" : response.claimText().toLowerCase(Locale.ROOT);

        List<String> forbiddenPhrases = List.of(
                "обратиться в суд",
                "в судебном порядке",
                "исковое заявление",
                "подать иск",
                "иск в суд",
                "арбитражный суд",
                "обращения в суд",
                "взыскание через суд"
        );

        for (String phrase : forbiddenPhrases) {
            if (text.contains(phrase)) {
                errors.add("Model used forbidden court/escalation phrase: " + phrase);
            }
        }
    }

    private void validateManualReview(GenerateClaimResponse response, List<String> errors) {
        if (!Boolean.TRUE.equals(response.manualReviewRequired())) {
            errors.add("manual_review_required must be true");
        }
    }

    private boolean sameBigDecimal(BigDecimal expected, BigDecimal actual) {
        if (expected == null && actual == null) {
            return true;
        }

        if (expected == null || actual == null) {
            return false;
        }

        return expected.compareTo(actual) == 0;
    }

    private boolean sameText(String expected, String actual) {
        if (expected == null && actual == null) {
            return true;
        }

        if (expected == null || actual == null) {
            return false;
        }

        return expected.trim().equalsIgnoreCase(actual.trim());
    }

    private String normalizeKey(String first, String second) {
        return (first == null ? "" : first.trim().toLowerCase(Locale.ROOT))
                + "::"
                + (second == null ? "" : second.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }
}
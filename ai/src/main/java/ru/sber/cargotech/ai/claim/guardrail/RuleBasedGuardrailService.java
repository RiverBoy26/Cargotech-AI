package ru.sber.cargotech.ai.claim.guardrail;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RuleBasedGuardrailService {

    private static final Pattern ARTICLE_REFERENCE_PATTERN = Pattern.compile(
            "(?iu)(?:^|[^\\p{L}\\p{N}])(?:статья|статьи|статью|статье|статьей|статьёй|статьями|статей|ст\\.?)\\s*(\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern ARTICLE_NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    private final ClaimFactConsistencyValidator factConsistencyValidator;

    public RuleBasedGuardrailService(ClaimFactConsistencyValidator factConsistencyValidator) {
        this.factConsistencyValidator = factConsistencyValidator;
    }

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
            factConsistencyValidator.validate(request, response, errors, warnings);
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
            errors.add("legal_context is required for claim generation");
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

        GenerateClaimRequest.PaymentStatus paymentStatus = request.caseFacts().payment().paymentStatus();
        if (paymentStatus != GenerateClaimRequest.PaymentStatus.UNPAID
                && paymentStatus != GenerateClaimRequest.PaymentStatus.PARTIALLY_PAID) {
            errors.add("payment_status must be UNPAID or PARTIALLY_PAID for PAYMENT_DELAY claim");
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
            errors.add("loading failure must be confirmed by dispatcher before a monetary claim is generated");
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

        if (calculation.principalDebt() != null
                && calculation.penaltyAmount() != null
                && calculation.totalAmount() != null
                && calculation.principalDebt().add(calculation.penaltyAmount()).compareTo(calculation.totalAmount()) != 0) {
            errors.add("backend_calculation.total_amount must equal principal_debt + penalty_amount");
        }

        if (claimType == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            if (calculation.originalObligationAmount() == null
                    || calculation.originalObligationAmount().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add("backend_calculation.original_obligation_amount must be positive for PAYMENT_DELAY");
            }

            if (calculation.paidAmount() == null || calculation.paidAmount().compareTo(BigDecimal.ZERO) < 0) {
                errors.add("backend_calculation.paid_amount must be zero or positive for PAYMENT_DELAY");
            }

            if (calculation.originalObligationAmount() != null
                    && calculation.principalDebt() != null
                    && calculation.originalObligationAmount().compareTo(calculation.principalDebt()) < 0) {
                errors.add("backend_calculation.original_obligation_amount must not be less than principal_debt");
            }

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
        Map<String, GenerateClaimRequest.ContractContextChunk> allowedByChunkId = new HashMap<>();

        for (GenerateClaimRequest.ContractContextChunk chunk : safeList(request.contractContext())) {
            if (chunk != null && !isBlank(chunk.chunkId())) {
                allowedByChunkId.put(chunk.chunkId(), chunk);
            }
        }

        List<GenerateClaimResponse.UsedContractClause> usedClauses = safeList(response.usedContractClauses());
        boolean numberedContractContextAvailable = safeList(request.contractContext()).stream()
                .filter(Objects::nonNull)
                .anyMatch(chunk -> !isBlank(chunk.clauseNumber()));

        if (usedClauses.isEmpty()) {
            if (request.caseFacts() != null
                    && request.caseFacts().claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY
                    && numberedContractContextAvailable) {
                errors.add("Model must cite at least one numbered contract clause from contract_context");
            } else {
                warnings.add("Model did not cite contract clauses");
            }
            return;
        }

        for (GenerateClaimResponse.UsedContractClause used : usedClauses) {
            if (used == null || isBlank(used.chunkId())) {
                errors.add("Model contract citation must contain chunk_id");
                continue;
            }

            GenerateClaimRequest.ContractContextChunk allowed = allowedByChunkId.get(used.chunkId());
            if (allowed == null) {
                errors.add("Model used unknown contract chunk_id: " + used.chunkId());
                continue;
            }

            if (!sameText(allowed.clauseNumber(), used.clauseNumber())) {
                errors.add("Model contract chunk_id and clause_number do not match: " + used.chunkId());
                continue;
            }

            if (!isBlank(used.clauseNumber())
                    && !containsContractClauseReference(response.claimText(), used.clauseNumber())) {
                errors.add("claim_text does not cite used contract clause: " + used.clauseNumber());
            }

            if (!isBlank(used.clauseNumber())
                    && request.caseFacts() != null
                    && request.caseFacts().claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY
                    && request.caseFacts().contract() != null
                    && !isBlank(request.caseFacts().contract().contractNumber())
                    && !containsClauseAndContractNumberInSameSentence(
                            response.claimText(),
                            used.clauseNumber(),
                            request.caseFacts().contract().contractNumber()
                    )) {
                errors.add("claim_text contract clause citation must include contract number in the same sentence: "
                        + used.clauseNumber());
            }
        }
    }

    private boolean containsContractClauseReference(String claimText, String clauseNumber) {
        if (isBlank(claimText) || isBlank(clauseNumber)) {
            return false;
        }

        // Legal Russian drafting commonly groups references:
        // "п. 8.2, 8.4 Договора" / "пп. 8.2 и 8.4".
        // Treat every number inside such a group as an explicit citation instead
        // of requiring a separate "п." marker before each number.
        String previousClauses = "(?:\\d+(?:\\.\\d+)+\\s*(?:,|;|и)\\s*)*";
        String marker = "(?:пункт(?:а|у|е|ом|ы|ов)?|п\\.|пп\\.)\\s*"
                + previousClauses
                + Pattern.quote(clauseNumber);
        return Pattern.compile("(?iu)" + marker).matcher(claimText).find();
    }

    private boolean containsClauseAndContractNumberInSameSentence(
            String claimText,
            String clauseNumber,
            String contractNumber
    ) {
        if (isBlank(claimText) || isBlank(clauseNumber) || isBlank(contractNumber)) {
            return false;
        }

        String previousClauses = "(?:\\d+(?:\\.\\d+)+\\s*(?:,|;|и)\\s*)*";
        String clauseMarker = "(?:пункт(?:а|у|е|ом|ы|ов)?|п\\.|пп\\.)\\s*"
                + previousClauses
                + Pattern.quote(clauseNumber);
        String contractMarker = "(?:договор\\p{L}*\\s*)?(?:№\\s*)?"
                + Pattern.quote(contractNumber);

        // Clause numbers (4.2) and contract dates (10.01.2026) themselves
        // contain dots, so a dot cannot be used as a sentence delimiter here.
        // Require both markers on the same logical line instead. This still
        // enforces a local, human-readable citation without rejecting normal
        // Russian legal formatting.
        Pattern linePattern = Pattern.compile(
                "(?iu)(?=[^\\n]*" + clauseMarker + ")"
                        + "(?=[^\\n]*" + contractMarker + ")"
                        + "[^\\n]+"
        );
        return linePattern.matcher(claimText).find();
    }

    private void validateUsedLawArticles(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        Map<String, GenerateClaimRequest.LegalContextItem> allowedByChunkId = new HashMap<>();
        for (GenerateClaimRequest.LegalContextItem item : safeList(request.legalContext())) {
            if (item == null || isBlank(item.lawCode()) || isBlank(item.article())) {
                continue;
            }
            if (!isBlank(item.chunkId())) {
                allowedByChunkId.put(item.chunkId(), item);
            }
        }

        List<GenerateClaimResponse.UsedLawArticle> usedArticles = safeList(response.usedLawArticles());

        if (usedArticles.isEmpty()) {
            errors.add("Model must cite at least one applicable law article from legal_context");
            validateNoUnknownLawReferences(request, response, errors);
            return;
        }

        for (GenerateClaimResponse.UsedLawArticle used : usedArticles) {
            if (used == null) {
                errors.add("response.used_law_articles contains null item");
                continue;
            }

            GenerateClaimRequest.LegalContextItem allowed = null;

            if (!allowedByChunkId.isEmpty()) {
                if (isBlank(used.chunkId())) {
                    errors.add("Model law citation must contain chunk_id");
                    continue;
                }

                allowed = allowedByChunkId.get(used.chunkId());
                if (allowed == null) {
                    errors.add("Model used unknown legal chunk_id: " + used.chunkId());
                    continue;
                }

                if (!sameLawSource(allowed.lawCode(), used.lawCode()) || !sameText(allowed.article(), used.article())) {
                    errors.add("Model legal chunk_id does not match law_code/article: " + used.chunkId());
                    continue;
                }
            } else if (!containsAllowedLawPair(request.legalContext(), used)) {
                errors.add("Model used law article not present in legal_context: " + used.lawCode() + " " + used.article());
                continue;
            } else {
                allowed = findLegacyLegalContextItem(request.legalContext(), used);
            }

            validateLawCitationPresentInClaimText(allowed, used, response.claimText(), errors);
        }

        validateNoUnknownLawReferences(request, response, errors);
    }

    private GenerateClaimRequest.LegalContextItem findLegacyLegalContextItem(
            List<GenerateClaimRequest.LegalContextItem> legalContext,
            GenerateClaimResponse.UsedLawArticle used
    ) {
        for (GenerateClaimRequest.LegalContextItem item : safeList(legalContext)) {
            if (item != null
                    && sameLawSource(item.lawCode(), used.lawCode())
                    && sameText(item.article(), used.article())) {
                return item;
            }
        }
        return null;
    }

    private void validateLawCitationPresentInClaimText(
            GenerateClaimRequest.LegalContextItem allowed,
            GenerateClaimResponse.UsedLawArticle used,
            String claimText,
            List<String> errors
    ) {
        if (isBlank(claimText)) {
            return;
        }

        String articleNumber = firstArticleNumber(used.article());
        if (articleNumber == null && allowed != null) {
            articleNumber = firstArticleNumber(allowed.article());
        }

        if (articleNumber == null || !extractReferencedArticleNumbers(claimText).contains(articleNumber)) {
            errors.add("claim_text does not cite used law article: " + used.lawCode() + " " + used.article());
            return;
        }

        if (!containsLawSourceReference(claimText, allowed, used)) {
            errors.add("claim_text cites article " + articleNumber + " without the expected law source: "
                    + expectedLawSourceLabel(allowed, used));
        }
    }

    /**
     * Legal citations are validated semantically rather than by exact string equality.
     * For example, both "ГК РФ, ст. 309" and "в соответствии со ст. 309 ГК РФ"
     * are the same reference and must be accepted.
     */
    private boolean containsLawSourceReference(
            String claimText,
            GenerateClaimRequest.LegalContextItem allowed,
            GenerateClaimResponse.UsedLawArticle used
    ) {
        String articleNumber = firstArticleNumber(used.article());
        if (articleNumber == null && allowed != null) {
            articleNumber = firstArticleNumber(allowed.article());
        }
        if (articleNumber == null) {
            return false;
        }

        String source = ((allowed == null ? "" : Objects.toString(allowed.lawCode(), ""))
                + " " + (allowed == null ? "" : Objects.toString(allowed.citation(), ""))
                + " " + Objects.toString(used.lawCode(), "")).toLowerCase(Locale.ROOT);

        String lawPattern = lawSourcePattern(source, allowed, used);
        if (lawPattern == null) {
            return true;
        }

        String articlePattern = "(?:статья|статьи|статью|статье|статьей|статьёй|статьями|статей|ст\\.?)\\s*"
                + "(?:\\d+(?:\\.\\d+)?\\s*(?:,|;|и)\\s*)*"
                + Pattern.quote(articleNumber);

        Pattern referencePattern = Pattern.compile(
                "(?isu)(?:"
                        + articlePattern + ".{0,120}?" + lawPattern
                        + "|"
                        + lawPattern + ".{0,120}?" + articlePattern
                        + ")"
        );

        return referencePattern.matcher(claimText).find();
    }

    private String lawSourcePattern(
            String source,
            GenerateClaimRequest.LegalContextItem allowed,
            GenerateClaimResponse.UsedLawArticle used
    ) {
        if (source.contains("гк рф") || source.contains("гражданск")) {
            return "(?:гк\\s*рф|гражданск\\p{L}*\\s+кодекс\\p{L}*\\s+российск\\p{L}*\\s+федерац\\p{L}*)";
        }

        if (source.contains("апк рф") || source.contains("арбитражн") && source.contains("процессуальн")) {
            return "(?:апк\\s*рф|арбитражн\\p{L}*\\s+процессуальн\\p{L}*\\s+кодекс\\p{L}*\\s+российск\\p{L}*\\s+федерац\\p{L}*)";
        }

        Matcher federalLawMatcher = Pattern.compile("(?iu)(\\d+)\\s*[-–—]?\\s*фз").matcher(source);
        if (federalLawMatcher.find()) {
            String lawNumber = Pattern.quote(federalLawMatcher.group(1));
            return "(?:федеральн\\p{L}*\\s+закон\\p{L}*\\s*(?:№\\s*)?"
                    + lawNumber
                    + "\\s*[-–—]?\\s*фз|"
                    + lawNumber
                    + "\\s*[-–—]?\\s*фз)";
        }

        String expected = allowed == null ? null : allowed.lawCode();
        if (isBlank(expected)) {
            expected = used.lawCode();
        }
        if (isBlank(expected)) {
            return null;
        }

        expected = expected.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        return expected.isBlank() ? null : Pattern.quote(expected);
    }

    private String expectedLawSourceLabel(
            GenerateClaimRequest.LegalContextItem allowed,
            GenerateClaimResponse.UsedLawArticle used
    ) {
        if (allowed != null && !isBlank(allowed.lawCode())) {
            return allowed.lawCode();
        }
        return used.lawCode();
    }

    private void validateNoUnknownLawReferences(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors
    ) {
        Set<String> allowedArticleNumbers = new HashSet<>();
        for (GenerateClaimRequest.LegalContextItem item : safeList(request.legalContext())) {
            if (item == null) {
                continue;
            }
            String articleNumber = firstArticleNumber(item.article());
            if (articleNumber != null) {
                allowedArticleNumbers.add(articleNumber);
            }
        }

        for (String referencedArticle : extractReferencedArticleNumbers(response.claimText())) {
            if (!allowedArticleNumbers.contains(referencedArticle)) {
                errors.add("claim_text cites law article not present in legal_context: " + referencedArticle);
            }
        }
    }

    private Set<String> extractReferencedArticleNumbers(String text) {
        if (isBlank(text)) {
            return Set.of();
        }

        Set<String> result = new LinkedHashSet<>();

        // Capture both standalone references ("ст. 395") and grouped references
        // ("ст. 309, 314 ГК РФ"). The old implementation only saw the first
        // article in a group and falsely blocked otherwise valid legal drafting.
        Pattern articleListPattern = Pattern.compile(
                "(?iu)(?:^|[^\\p{L}\\p{N}])"
                        + "(?:статья|статьи|статью|статье|статьей|статьёй|статьями|статей|ст\\.?)\\s*"
                        + "((?:\\d+(?:\\.\\d+)?)(?:\\s*(?:,|;|и)\\s*\\d+(?:\\.\\d+)?)*)"
        );
        Matcher listMatcher = articleListPattern.matcher(text);
        while (listMatcher.find()) {
            Matcher numberMatcher = ARTICLE_NUMBER_PATTERN.matcher(listMatcher.group(1));
            while (numberMatcher.find()) {
                result.add(numberMatcher.group());
            }
        }

        return result;
    }

    private String firstArticleNumber(String article) {
        if (isBlank(article)) {
            return null;
        }
        Matcher matcher = ARTICLE_NUMBER_PATTERN.matcher(article);
        return matcher.find() ? matcher.group() : null;
    }

    private String normalizeCitationText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private void validateForbiddenText(GenerateClaimResponse response, List<String> errors) {
        String text = ((response.claimText() == null ? "" : response.claimText())
                + "\n"
                + (response.summaryForLawyer() == null ? "" : response.summaryForLawyer()))
                .toLowerCase(Locale.ROOT);

        // A neutral warning about the creditor's right to go to court after non-performance
        // of the claim is allowed by the product requirements. Concrete procedural actions,
        // invented courts and aggressive escalation language remain prohibited.
        String textWithoutAllowedCourtWarning = text.replaceAll(
                "(?iu)в\\s+случае\\s+неисполнени\\p{L}*[^.]{0,180}?"
                        + "(?:вправе|имеет\\s+право)[^.]{0,80}?обратиться\\s+в\\s+суд",
                ""
        );

        List<String> forbiddenPhrases = List.of(
                "в судебном порядке",
                "исковое заявление",
                "подать иск",
                "иск в суд",
                "арбитражный суд",
                "обращения в суд",
                "взыскание через суд"
        );

        for (String phrase : forbiddenPhrases) {
            if (textWithoutAllowedCourtWarning.contains(phrase)) {
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

    /**
     * Compares structured law source labels semantically. RAG may use a full
     * official label (for example, "ГК РФ (часть первая)"), while the model
     * returns the conventional short form ("ГК РФ"). These are the same
     * source and must not be rejected before claim-text citation validation.
     */
    private boolean sameLawSource(String expected, String actual) {
        if (expected == null && actual == null) {
            return true;
        }
        if (expected == null || actual == null) {
            return false;
        }

        return canonicalLawSource(expected).equals(canonicalLawSource(actual));
    }

    private String canonicalLawSource(String value) {
        String normalized = normalizeCitationText(value);

        if (normalized.contains("гк рф")
                || (normalized.contains("гражданск")
                && normalized.contains("кодекс")
                && !normalized.contains("процессуальн"))) {
            return "GK_RF";
        }

        if (normalized.contains("апк рф")
                || (normalized.contains("арбитражн")
                && normalized.contains("процессуальн")
                && normalized.contains("кодекс"))) {
            return "APK_RF";
        }

        Matcher federalLawMatcher = Pattern.compile("(?iu)(\\d+)\\s*[-–—]?\\s*фз").matcher(value);
        if (federalLawMatcher.find()) {
            return "FZ_" + federalLawMatcher.group(1);
        }

        // Parenthetical clarifications such as "(часть первая)" are metadata,
        // not a different source. Keep other labels strict after removing them.
        return normalizeCitationText(value.replaceAll("\\([^)]*\\)", " "));
    }

    private boolean containsAllowedLawPair(
            List<GenerateClaimRequest.LegalContextItem> legalContext,
            GenerateClaimResponse.UsedLawArticle used
    ) {
        for (GenerateClaimRequest.LegalContextItem item : safeList(legalContext)) {
            if (item != null
                    && sameLawSource(item.lawCode(), used.lawCode())
                    && sameText(item.article(), used.article())) {
                return true;
            }
        }
        return false;
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

package ru.sber.cargotech.ai.claim.guardrail;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ClaimFactConsistencyValidator {

    private static final Pattern INN_PATTERN = Pattern.compile("(?<!\\d)(?:\\d{10}|\\d{12})(?!\\d)");
    private static final Pattern RUB_AMOUNT_PATTERN = Pattern.compile(
            "(?iu)(?<!\\d)(\\d[\\d \\u00A0]{0,18}(?:[,.]\\d{1,2})?)\\s*(?:руб(?:лей|ля|ль|\\.)?|₽)"
    );
    private static final DateTimeFormatter INPUT_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    public void validate(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        if (request == null || request.caseFacts() == null || response == null || response.claimText() == null) {
            return;
        }

        String text = response.claimText();
        GenerateClaimRequest.CaseFacts facts = request.caseFacts();

        validateParty("creditor", facts.creditor(), text, errors);
        validateParty("debtor", facts.debtor(), text, errors);
        validateUnknownInns(facts, text, errors);
        validateContract(facts.contract(), text, errors);
        validateAmounts(request.backendCalculation(), text, errors);

        if (facts.claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            validatePaymentDelayFacts(facts, text, errors);
        } else if (facts.claimType() == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            validateLoadingFailureFacts(facts, text, errors);
        }

        validateAttachments(request, response, errors, warnings);

        for (String modelWarning : safeList(response.warnings())) {
            if (modelWarning != null && !modelWarning.isBlank()) {
                warnings.add("Model warning: " + modelWarning.trim());
            }
        }
    }

    private void validateParty(
            String role,
            GenerateClaimRequest.Party party,
            String text,
            List<String> errors
    ) {
        if (party == null) {
            return;
        }

        requireTextValue(text, party.name(), role + ".name", errors);
        requireTextValue(text, party.inn(), role + ".inn", errors);
    }

    private void validateUnknownInns(
            GenerateClaimRequest.CaseFacts facts,
            String text,
            List<String> errors
    ) {
        Set<String> allowed = new HashSet<>();
        if (facts.creditor() != null && hasText(facts.creditor().inn())) allowed.add(digits(facts.creditor().inn()));
        if (facts.debtor() != null && hasText(facts.debtor().inn())) allowed.add(digits(facts.debtor().inn()));

        Matcher matcher = INN_PATTERN.matcher(text);
        while (matcher.find()) {
            String found = digits(matcher.group());
            if (!allowed.contains(found)) {
                errors.add("claim_text contains unknown INN: " + found);
            }
        }
    }

    private void validateContract(
            GenerateClaimRequest.ContractFacts contract,
            String text,
            List<String> errors
    ) {
        if (contract == null) {
            return;
        }
        requireTextValue(text, contract.contractNumber(), "contract.contract_number", errors);
        requireDate(text, contract.contractDate(), "contract.contract_date", errors);
    }

    private void validatePaymentDelayFacts(
            GenerateClaimRequest.CaseFacts facts,
            String text,
            List<String> errors
    ) {
        GenerateClaimRequest.PaymentFacts payment = facts.payment();
        if (payment != null) {
            requireDate(text, payment.paymentDueDate(), "payment.payment_due_date", errors);
        }

        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();
        if (shipment == null) {
            return;
        }

        requireTextValue(text, shipment.actNumber(), "shipment.act_number", errors);
        requireDate(text, shipment.actDate(), "shipment.act_date", errors);
        requireTextValue(text, shipment.ttnNumber(), "shipment.ttn_number", errors);
        requireTextValue(text, shipment.invoiceNumber(), "shipment.invoice_number", errors);
        requireTextValue(text, shipment.route(), "shipment.route", errors);
    }

    private void validateLoadingFailureFacts(
            GenerateClaimRequest.CaseFacts facts,
            String text,
            List<String> errors
    ) {
        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();
        if (shipment == null) {
            return;
        }

        requireTextValue(text, shipment.orderNumber(), "shipment.order_number", errors);
        requireDate(text, shipment.loadingDate(), "shipment.loading_date", errors);
        requireTextValue(text, shipment.loadingAddress(), "shipment.loading_address", errors);
        requireTextValue(text, shipment.loadingTimeWindow(), "shipment.loading_time_window", errors);
        requireTextValue(text, shipment.route(), "shipment.route", errors);
    }

    private void validateAmounts(
            GenerateClaimRequest.BackendCalculation calculation,
            String text,
            List<String> errors
    ) {
        if (calculation == null) {
            return;
        }

        Set<BigDecimal> allowed = new HashSet<>();
        addAmount(allowed, calculation.principalDebt());
        addAmount(allowed, calculation.penaltyAmount());
        addAmount(allowed, calculation.totalAmount());

        if (calculation.principalDebt() != null && calculation.principalDebt().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.principalDebt(), "principal_debt", errors);
        }
        if (calculation.penaltyAmount() != null && calculation.penaltyAmount().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.penaltyAmount(), "penalty_amount", errors);
        }
        if (calculation.totalAmount() != null && calculation.totalAmount().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.totalAmount(), "total_amount", errors);
        }

        Matcher matcher = RUB_AMOUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            BigDecimal found = parseAmount(matcher.group(1));
            if (found != null && !containsAmount(allowed, found)) {
                errors.add("claim_text contains amount not present in backend_calculation: " + found.toPlainString() + " RUB");
            }
        }
    }

    private void validateAttachments(
            GenerateClaimRequest request,
            GenerateClaimResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        Set<GenerateClaimResponse.DocumentType> allowed = EnumSet.noneOf(GenerateClaimResponse.DocumentType.class);
        GenerateClaimRequest.CaseFacts facts = request.caseFacts();
        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();

        if (facts.contract() != null) allowed.add(GenerateClaimResponse.DocumentType.CONTRACT);
        if (request.backendCalculation() != null) allowed.add(GenerateClaimResponse.DocumentType.CALCULATION);
        if (facts.payment() != null && Boolean.TRUE.equals(facts.payment().paymentConfirmedByAccountant())) {
            allowed.add(GenerateClaimResponse.DocumentType.PAYMENT_EXTRACT);
        }
        if (shipment != null) {
            if (hasText(shipment.actNumber()) || hasText(shipment.actDate())) allowed.add(GenerateClaimResponse.DocumentType.ACT);
            if (hasText(shipment.ttnNumber())) allowed.add(GenerateClaimResponse.DocumentType.TTN);
            if (hasText(shipment.invoiceNumber())) allowed.add(GenerateClaimResponse.DocumentType.INVOICE);
            if (hasText(shipment.orderNumber())) allowed.add(GenerateClaimResponse.DocumentType.TRANSPORT_ORDER);
            if (Boolean.TRUE.equals(shipment.failureConfirmedByDispatcher())) {
                allowed.add(GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT);
            }
        }

        for (GenerateClaimResponse.Attachment attachment : safeList(response.attachments())) {
            if (attachment == null || attachment.documentType() == null) {
                errors.add("response.attachments contains item without document_type");
                continue;
            }
            if (attachment.documentType() == GenerateClaimResponse.DocumentType.OTHER
                    || !allowed.contains(attachment.documentType())) {
                errors.add("Model added unsupported attachment: " + attachment.documentType());
            }
        }

        if (response.attachments() == null || response.attachments().isEmpty()) {
            warnings.add("Model returned no attachments");
        }
    }

    private void requireTextValue(String text, String expected, String field, List<String> errors) {
        if (!hasText(expected)) {
            return;
        }
        if (!normalize(text).contains(normalize(expected))) {
            errors.add("claim_text does not contain expected " + field + ": " + expected);
        }
    }

    private void requireDate(String text, String expected, String field, List<String> errors) {
        if (!hasText(expected)) {
            return;
        }
        if (!containsDate(text, expected)) {
            errors.add("claim_text does not contain expected " + field + ": " + expected);
        }
    }

    private boolean containsDate(String text, String expected) {
        if (normalize(text).contains(normalize(expected))) {
            return true;
        }
        try {
            LocalDate date = LocalDate.parse(expected, INPUT_DATE);
            String numericDash = date.format(DateTimeFormatter.ofPattern("dd-MM-uuuu"));
            String iso = date.toString();
            String longDate = date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
            String normalizedText = normalize(text);
            return normalizedText.contains(normalize(numericDash))
                    || normalizedText.contains(normalize(iso))
                    || normalizedText.contains(normalize(longDate));
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private void requireAmount(String text, BigDecimal amount, String field, List<String> errors) {
        Matcher matcher = RUB_AMOUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            BigDecimal found = parseAmount(matcher.group(1));
            if (found != null && sameAmount(found, amount)) {
                return;
            }
        }
        errors.add("claim_text does not contain expected " + field + ": " + amount.toPlainString() + " RUB");
    }

    private BigDecimal parseAmount(String raw) {
        try {
            return new BigDecimal(raw.replace(" ", "").replace("\u00A0", "").replace(',', '.'));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void addAmount(Set<BigDecimal> values, BigDecimal value) {
        if (value != null) values.add(value.stripTrailingZeros());
    }

    private boolean containsAmount(Set<BigDecimal> values, BigDecimal candidate) {
        return values.stream().anyMatch(value -> sameAmount(value, candidate));
    }

    private boolean sameAmount(BigDecimal first, BigDecimal second) {
        return first != null && second != null
                && first.setScale(2, RoundingMode.HALF_UP).compareTo(second.setScale(2, RoundingMode.HALF_UP)) == 0;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('—', '-')
                .replace('–', '-')
                .replaceAll("[\\p{Punct}«»„“”]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}

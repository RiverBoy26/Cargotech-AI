package ru.sber.cargotech.ai.document.guardrail;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailDecision;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentGuardrailService {

    private static final Pattern INN = Pattern.compile("(?<!\\d)(?:\\d{10}|\\d{12})(?!\\d)");
    private static final Pattern RUB_AMOUNT = Pattern.compile(
            "(?iu)(?<!\\d)\\d[\\d \\u00A0]{0,18}(?:[,.]\\d{1,2})?\\s*(?:руб(?:лей|ля|ль|\\.)?|₽)"
    );
    private static final DateTimeFormatter INPUT_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

    public GuardrailResult check(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            GenerateClaimResponse.DocumentType expectedType
    ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (request == null || request.caseFacts() == null) {
            errors.add("case_facts is required");
        } else {
            if (request.caseFacts().claimType() != GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
                errors.add("Only LOADING_FAILURE supports these documents");
            }
            if (request.caseFacts().shipment() == null) {
                errors.add("case_facts.shipment is required");
            } else if (!Boolean.TRUE.equals(request.caseFacts().shipment().failureConfirmedByDispatcher())) {
                errors.add("loading failure must be confirmed by dispatcher before document generation");
            }
        }

        if (response == null) {
            errors.add("Response is null");
        } else {
            if (response.documentType() != expectedType) errors.add("Model changed document_type");
            if (!hasText(response.documentTitle())) errors.add("document_title is required");
            if (!hasText(response.documentText())) errors.add("document_text is required");
            if (!Boolean.TRUE.equals(response.manualReviewRequired())) errors.add("manual_review_required must be true");
            validateText(request, response, expectedType, errors);
            validateClauses(request, response, errors, warnings);
            validateAttachments(request, response, errors, warnings);
            for (String warning : safeList(response.warnings())) {
                if (warning != null && !warning.isBlank()) warnings.add("Model warning: " + warning.trim());
            }
        }

        GuardrailDecision decision = !errors.isEmpty()
                ? GuardrailDecision.BLOCK
                : (!warnings.isEmpty() ? GuardrailDecision.REVIEW : GuardrailDecision.PASS);
        return new GuardrailResult(decision, List.copyOf(errors), List.copyOf(warnings));
    }

    private void validateText(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            GenerateClaimResponse.DocumentType expectedType,
            List<String> errors
    ) {
        String text = response.documentText() == null ? "" : response.documentText();
        String normalized = normalize(text);

        if (request != null && request.caseFacts() != null) {
            GenerateClaimRequest.CaseFacts facts = request.caseFacts();
            require(normalized, facts.creditor() == null ? null : facts.creditor().name(), "creditor.name", errors);
            require(normalized, facts.debtor() == null ? null : facts.debtor().name(), "debtor.name", errors);
            require(normalized, facts.creditor() == null ? null : facts.creditor().inn(), "creditor.inn", errors);
            require(normalized, facts.debtor() == null ? null : facts.debtor().inn(), "debtor.inn", errors);
            validateUnknownInns(facts, text, errors);
            if (facts.contract() != null) {
                require(normalized, facts.contract().contractNumber(), "contract.contract_number", errors);
            }
            if (facts.shipment() != null) {
                require(normalized, facts.shipment().orderNumber(), "shipment.order_number", errors);
                requireDate(text, facts.shipment().loadingDate(), "shipment.loading_date", errors);
                require(normalized, facts.shipment().loadingAddress(), "shipment.loading_address", errors);
                require(normalized, facts.shipment().loadingTimeWindow(), "shipment.loading_time_window", errors);
                require(normalized, facts.shipment().route(), "shipment.route", errors);
            }
        }

        if (RUB_AMOUNT.matcher(text).find()) {
            errors.add("Fixation document contains a monetary amount");
        }

        for (String phrase : List.of("обратиться в суд", "в судебном порядке", "исковое заявление", "подать иск", "арбитражный суд")) {
            if (normalized.contains(normalize(phrase))) errors.add("Document contains forbidden court phrase: " + phrase);
        }
        for (String phrase : List.of("требуем оплатить", "уплатить штраф", "оплатить неустойку", "взыскать")) {
            if (normalized.contains(normalize(phrase))) errors.add("Fixation document contains monetary demand: " + phrase);
        }
        if (expectedType == GenerateClaimResponse.DocumentType.NOTIFICATION
                && normalized.contains("настоящая претензия")) {
            errors.add("Notification is incorrectly presented as a claim");
        }
    }

    private void validateClauses(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        Map<String, GenerateClaimRequest.ContractContextChunk> allowed = new HashMap<>();
        if (request != null) {
            for (GenerateClaimRequest.ContractContextChunk chunk : safeList(request.contractContext())) {
                if (chunk != null && hasText(chunk.chunkId())) allowed.put(chunk.chunkId(), chunk);
            }
        }
        if (safeList(response.usedContractClauses()).isEmpty()) {
            warnings.add("Model did not cite contract clauses");
            return;
        }
        for (GenerateClaimResponse.UsedContractClause used : safeList(response.usedContractClauses())) {
            if (used == null || !hasText(used.chunkId())) {
                errors.add("Document contract citation must contain chunk_id");
                continue;
            }
            GenerateClaimRequest.ContractContextChunk source = allowed.get(used.chunkId());
            if (source == null) {
                errors.add("Document used unknown contract chunk_id: " + used.chunkId());
            } else if (!Objects.equals(normalize(source.clauseNumber()), normalize(used.clauseNumber()))) {
                errors.add("Document contract chunk_id and clause_number do not match: " + used.chunkId());
            }
        }
    }

    private void validateAttachments(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        boolean orderExists = request != null && request.caseFacts() != null
                && request.caseFacts().shipment() != null
                && hasText(request.caseFacts().shipment().orderNumber());
        for (GenerateClaimResponse.Attachment attachment : safeList(response.attachments())) {
            if (attachment == null || attachment.documentType() == null) {
                errors.add("attachments contains item without document_type");
            } else if (attachment.documentType() != GenerateClaimResponse.DocumentType.TRANSPORT_ORDER || !orderExists) {
                errors.add("Document added unsupported attachment: " + attachment.documentType());
            }
        }
        if (response.attachments() == null) warnings.add("response.attachments is null");
    }

    private void validateUnknownInns(
            GenerateClaimRequest.CaseFacts facts,
            String text,
            List<String> errors
    ) {
        Set<String> allowed = new HashSet<>();
        if (facts.creditor() != null && hasText(facts.creditor().inn())) {
            allowed.add(facts.creditor().inn().replaceAll("\\D", ""));
        }
        if (facts.debtor() != null && hasText(facts.debtor().inn())) {
            allowed.add(facts.debtor().inn().replaceAll("\\D", ""));
        }

        Matcher matcher = INN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String found = matcher.group().replaceAll("\\D", "");
            if (!allowed.contains(found)) {
                errors.add("document_text contains unknown INN: " + found);
            }
        }
    }

    private void requireDate(String text, String value, String field, List<String> errors) {
        if (!hasText(value)) return;
        String normalizedText = normalize(text);
        if (normalizedText.contains(normalize(value))) return;

        try {
            LocalDate date = LocalDate.parse(value, INPUT_DATE);
            String longDate = date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear();
            String dashDate = date.format(DateTimeFormatter.ofPattern("dd-MM-uuuu"));
            if (normalizedText.contains(normalize(longDate))
                    || normalizedText.contains(normalize(dashDate))
                    || normalizedText.contains(normalize(date.toString()))) {
                return;
            }
        } catch (DateTimeParseException ignored) {
            // Fall through to a deterministic validation error.
        }

        errors.add("document_text does not contain expected " + field + ": " + value);
    }

    private void require(String normalizedText, String value, String field, List<String> errors) {
        if (hasText(value) && !normalizedText.contains(normalize(value))) {
            errors.add("document_text does not contain expected " + field + ": " + value);
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('—', '-').replace('–', '-')
                .replaceAll("[\\p{Punct}«»„“”]", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}

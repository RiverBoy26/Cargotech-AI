package ru.sber.cargotech.claim.service;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.claim.dto.ContractExtractionCandidateRequest;
import ru.sber.cargotech.claim.dto.SubmitContractExtractionRequest;
import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.ContractExtractionField;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContractTextExtractionService {

    private static final Pattern DAYS = Pattern.compile("(?iu)(\\d{1,3})\\s*(?:рабоч(?:их|ие)|календарн(?:ых|ые))?\\s*(?:дн(?:ей|я|ь))");
    private static final Pattern RATE = Pattern.compile("(?iu)(\\d{1,3}(?:[.,]\\d{1,6})?)\\s*%");
    private static final Pattern CLAUSE_NUMBER = Pattern.compile("(?iu)^(?:п(?:ункт)?\\.?\\s*)?(\\d+(?:\\.\\d+)+)\\.?");
    private static final Pattern PAGE_MARKER = Pattern.compile("^\\[\\[PAGE:(\\d+)]]$");
    private static final Pattern CONTRACT_NUMBER = Pattern.compile(
        "(?iu)договор(?:-заявка)?[^\\r\\n№]{0,80}(?:№|N)\\s*([\\p{L}\\p{N}][\\p{L}\\p{N}./_-]{0,127})"
    );
    private static final Pattern CONTRACT_DATE_NUMERIC = Pattern.compile(
        "(?iu)договор(?:-заявка)?[^\\r\\n]{0,160}?от\\s+(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})"
    );
    private static final Pattern CONTRACT_DATE_TEXT = Pattern.compile(
        "(?iu)договор(?:-заявка)?[^\\r\\n]{0,160}?от\\s+(\\d{1,2})\\s+" +
            "(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4})"
    );
    private static final List<ContractExtractionField> REVIEW_FIELDS = List.of(
        ContractExtractionField.CONTRACT_NUMBER,
        ContractExtractionField.SIGNED_AT,
        ContractExtractionField.PAYMENT_DAYS,
        ContractExtractionField.PAYMENT_START_EVENT,
        ContractExtractionField.PENALTY_TYPE,
        ContractExtractionField.PENALTY_RATE,
        ContractExtractionField.CLAIM_RESPONSE_DAYS,
        ContractExtractionField.JURISDICTION
    );
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
        Map.entry("января", 1), Map.entry("февраля", 2), Map.entry("марта", 3),
        Map.entry("апреля", 4), Map.entry("мая", 5), Map.entry("июня", 6),
        Map.entry("июля", 7), Map.entry("августа", 8), Map.entry("сентября", 9),
        Map.entry("октября", 10), Map.entry("ноября", 11), Map.entry("декабря", 12)
    );

    public SubmitContractExtractionRequest extract(String sourceText) {
        String text = normalize(sourceText);
        Map<ContractExtractionField, ContractExtractionCandidateRequest> scalars = new LinkedHashMap<>();
        List<ContractExtractionCandidateRequest> clauses = new ArrayList<>();

        for (TextFragment textFragment : fragments(text)) {
            String fragment = textFragment.text();
            int page = textFragment.page();
            String lower = fragment.toLowerCase(Locale.ROOT);

            extractContractHeader(fragment, page, scalars);

            if (containsAny(lower, "оплат", "расчет", "расчёт") && containsAny(lower, "дн", "срок")) {
                Matcher days = DAYS.matcher(fragment);
                if (days.find()) {
                    putOnce(scalars, candidate(ContractExtractionField.PAYMENT_DAYS, days.group(1), fragment, page, confidence("0.92"), null));
                    PaymentStartEvent event = paymentStartEvent(lower);
                    if (event != null) {
                        putOnce(scalars, candidate(ContractExtractionField.PAYMENT_START_EVENT, event.name(), fragment, page, confidence("0.88"), null));
                    }
                    addClauseOnce(clauses, clause(fragment, page, ClauseType.PAYMENT_TERMS));
                }
            }

            if (containsAny(lower, "неустойк", "пеня", "пени", "ст. 395", "статья 395")) {
                PenaltyType type = containsAny(lower, "ст. 395", "статья 395", "ключев", "рефинанс")
                    ? PenaltyType.ARTICLE_395
                    : PenaltyType.CONTRACT_PENALTY;
                putOnce(scalars, candidate(ContractExtractionField.PENALTY_TYPE, type.name(), fragment, page, confidence("0.91"), null));
                Matcher rate = RATE.matcher(fragment);
                if (rate.find()) {
                    putOnce(scalars, candidate(
                        ContractExtractionField.PENALTY_RATE,
                        rate.group(1).replace(',', '.'), fragment, page, confidence("0.94"), null
                    ));
                }
                addClauseOnce(clauses, clause(fragment, page, ClauseType.PENALTY));
            }

            if (containsAny(lower, "претензи") && containsAny(lower, "дн", "срок", "ответ")) {
                Matcher days = DAYS.matcher(fragment);
                if (days.find()) {
                    putOnce(scalars, candidate(ContractExtractionField.CLAIM_RESPONSE_DAYS, days.group(1), fragment, page, confidence("0.89"), null));
                }
                addClauseOnce(clauses, clause(fragment, page, ClauseType.CLAIM_PROCEDURE));
            }

            if (containsAny(lower, "подсудн", "арбитражный суд", "арбитражном суде")) {
                putOnce(scalars, candidate(ContractExtractionField.JURISDICTION, fragment, fragment, page, confidence("0.86"), null));
                addClauseOnce(clauses, clause(fragment, page, ClauseType.JURISDICTION));
            }
        }

        List<ContractExtractionCandidateRequest> result = new ArrayList<>();
        for (ContractExtractionField field : REVIEW_FIELDS) {
            result.add(scalars.getOrDefault(field, missingCandidate(field)));
        }
        result.addAll(clauses);
        return new SubmitContractExtractionRequest(List.copyOf(result));
    }

    private List<TextFragment> fragments(String text) {
        List<TextFragment> result = new ArrayList<>();
        int page = 1;
        for (String paragraph : text.split("\\n+")) {
            String value = paragraph.trim();
            Matcher marker = PAGE_MARKER.matcher(value);
            if (marker.matches()) {
                page = Integer.parseInt(marker.group(1));
                continue;
            }
            if (value.length() < 8) continue;
            if (value.length() <= 1_500) {
                result.add(new TextFragment(value, page));
                continue;
            }
            for (String sentence : value.split("(?<=[.!?;])\\s+")) {
                if (sentence.length() >= 8) result.add(new TextFragment(trimTo(sentence, 1_500), page));
            }
        }
        return result;
    }

    private ContractExtractionCandidateRequest clause(String fragment, int page, ClauseType type) {
        return candidate(ContractExtractionField.EXACT_CLAUSE, fragment, fragment, page, confidence("0.99"), type);
    }

    private ContractExtractionCandidateRequest candidate(
        ContractExtractionField field,
        String value,
        String source,
        Integer sourcePage,
        BigDecimal confidence,
        ClauseType clauseType
    ) {
        return new ContractExtractionCandidateRequest(
            field,
            value,
            trimTo(source, 2_000),
            sourcePage,
            confidence,
            clauseNumber(source),
            clauseType,
            false
        );
    }

    private ContractExtractionCandidateRequest missingCandidate(ContractExtractionField field) {
        return new ContractExtractionCandidateRequest(field, null, null, null, null, null, null, false);
    }

    private void extractContractHeader(
        String fragment,
        int page,
        Map<ContractExtractionField, ContractExtractionCandidateRequest> scalars
    ) {
        Matcher number = CONTRACT_NUMBER.matcher(fragment);
        if (number.find()) {
            String value = number.group(1).replaceFirst("[.,;:]+$", "");
            if (!value.isBlank()) {
                putOnce(scalars, candidate(
                    ContractExtractionField.CONTRACT_NUMBER,
                    value,
                    fragment,
                    page,
                    confidence("0.94"),
                    null
                ));
            }
        }

        Matcher numericDate = CONTRACT_DATE_NUMERIC.matcher(fragment);
        if (numericDate.find()) {
            putDateOnce(scalars, fragment, page, numericDate.group(3), numericDate.group(2), numericDate.group(1));
            return;
        }
        Matcher textDate = CONTRACT_DATE_TEXT.matcher(fragment);
        if (textDate.find()) {
            Integer month = MONTHS.get(textDate.group(2).toLowerCase(Locale.ROOT));
            if (month != null) {
                putDateOnce(scalars, fragment, page, textDate.group(3), month.toString(), textDate.group(1));
            }
        }
    }

    private void putDateOnce(
        Map<ContractExtractionField, ContractExtractionCandidateRequest> scalars,
        String source,
        int page,
        String year,
        String month,
        String day
    ) {
        try {
            LocalDate value = LocalDate.of(
                Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)
            );
            putOnce(scalars, candidate(
                ContractExtractionField.SIGNED_AT,
                value.toString(),
                source,
                page,
                confidence("0.90"),
                null
            ));
        } catch (DateTimeException | NumberFormatException ignored) {
            // Invalid or ambiguous dates remain empty for human review.
        }
    }

    private void putOnce(
        Map<ContractExtractionField, ContractExtractionCandidateRequest> target,
        ContractExtractionCandidateRequest value
    ) {
        target.putIfAbsent(value.field(), value);
    }

    private void addClauseOnce(List<ContractExtractionCandidateRequest> target, ContractExtractionCandidateRequest value) {
        boolean exists = target.stream().anyMatch(item -> item.value().equals(value.value()));
        if (!exists) target.add(value);
    }

    private PaymentStartEvent paymentStartEvent(String lower) {
        if (containsAny(lower, "ттн", "товарно-транспорт")) return PaymentStartEvent.TTN_SIGNED;
        if (containsAny(lower, "акт", "акта")) return PaymentStartEvent.ACT_SIGNED;
        if (containsAny(lower, "разгруз", "выгруз")) return PaymentStartEvent.UNLOADING_DATE;
        if (containsAny(lower, "счет", "счёт", "инвойс")) return PaymentStartEvent.INVOICE_DATE;
        return null;
    }

    private String clauseNumber(String source) {
        Matcher matcher = CLAUSE_NUMBER.matcher(source.stripLeading());
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u0000', ' ')
            .replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll("[ ]{2,}", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private String trimTo(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private BigDecimal confidence(String value) {
        return new BigDecimal(value);
    }

    private record TextFragment(String text, int page) {}
}

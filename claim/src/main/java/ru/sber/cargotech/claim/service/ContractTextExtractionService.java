package ru.sber.cargotech.claim.service;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.claim.dto.ContractExtractionCandidateRequest;
import ru.sber.cargotech.claim.dto.SubmitContractExtractionRequest;
import ru.sber.cargotech.claim.enums.ClauseType;
import ru.sber.cargotech.claim.enums.ContractExtractionField;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.PenaltyType;
import ru.sber.cargotech.claim.enums.TermDayType;

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

    /**
     * Real contracts commonly duplicate the numeric value in words:
     * "45 (сорока пяти) календарных дней". The old regexp skipped such clauses
     * and then could pick an unrelated later "30 календарных дней" instead.
     */
    private static final Pattern DAYS = Pattern.compile(
        "(?iu)(\\d{1,3})\\s*(?:\\([^\\r\\n)]{1,80}\\)\\s*)?" +
            "(?:(рабоч\\p{L}*|календарн\\p{L}*|банковск\\p{L}*)\\s+)?дн(?:ей|я|ь)(?!\\p{L})"
    );
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
    private static final Pattern PAYMENT_TERM_SIGNAL = Pattern.compile(
        "(?iu)(?:" +
            "(?:клиент|заказчик|плательщик).{0,140}(?:производит|осуществляет|обязан|обязуется|оплачивает).{0,100}оплат\\p{L}*" +
            "|оплат\\p{L}*.{0,140}(?:осуществля\\p{L}*|производ\\p{L}*|подлеж\\p{L}*|в\\s+течение|не\\s+позднее|после\\s+истечения|срок\\p{L}*)" +
            "|срок\\p{L}*\\s+оплат\\p{L}*" +
            ")"
    );
    private static final Pattern PAYMENT_WORD = Pattern.compile("(?iu)(?:оплат\\p{L}*|плат[её]ж\\p{L}*|задолженн\\p{L}*)");
    private static final Pattern PENALTY_WORD = Pattern.compile("(?iu)(?:неустойк\\p{L}*|(?<!\\p{L})пен(?:я|и|ей|ю)(?!\\p{L}))");
    private static final Pattern PAYMENT_DELAY_WORD = Pattern.compile("(?iu)(?:просроч\\p{L}*|нарушен\\p{L}*\\s+срок\\p{L}*\\s+оплат\\p{L}*)");
    private static final Pattern INVOICE_WORD = Pattern.compile("(?iu)(?:(?<!\\p{L})сч[её]т(?:а|у|ом|е|ы|ов)?(?!\\p{L})|инвойс\\p{L}*)");
    private static final Pattern ARBITRATION_COURT = Pattern.compile(
        "(?iu)(арбитражн(?:ый|ом)\\s+суд(?:е)?\\s+[\\p{L}0-9№«»\"()\\-\\s]{2,140}?)(?=[,.;]|$)"
    );

    private static final List<ContractExtractionField> REVIEW_FIELDS = List.of(
        ContractExtractionField.CONTRACT_NUMBER,
        ContractExtractionField.SIGNED_AT,
        ContractExtractionField.PAYMENT_DAYS,
        ContractExtractionField.PAYMENT_DAY_TYPE,
        ContractExtractionField.PAYMENT_START_EVENT,
        ContractExtractionField.PENALTY_TYPE,
        ContractExtractionField.PENALTY_RATE,
        ContractExtractionField.CLAIM_RESPONSE_DAYS,
        ContractExtractionField.CLAIM_RESPONSE_DAY_TYPE,
        ContractExtractionField.JURISDICTION
    );
    private static final Map<String, Integer> MONTHS = Map.ofEntries(
        Map.entry("января", 1), Map.entry("февраля", 2), Map.entry("марта", 3),
        Map.entry("апреля", 4), Map.entry("мая", 5), Map.entry("июня", 6),
        Map.entry("июля", 7), Map.entry("августа", 8), Map.entry("сентября", 9),
        Map.entry("октября", 10), Map.entry("ноября", 11), Map.entry("декабря", 12)
    );

    /** Used by unit tests and already page-marked text. */
    public SubmitContractExtractionRequest extract(String sourceText) {
        return extract(sourceText, null);
    }

    /**
     * extractionMethod is used only to avoid fake DOCX page numbers. Apache POI
     * exposes a document page count, but XWPFWordExtractor does not tell which
     * paragraph is on which page. For DOCX sourcePage therefore stays null.
     */
    public SubmitContractExtractionRequest extract(String sourceText, String extractionMethod) {
        String text = normalize(sourceText);
        Map<ContractExtractionField, ContractExtractionCandidateRequest> scalars = new LinkedHashMap<>();
        List<ContractExtractionCandidateRequest> clauses = new ArrayList<>();
        boolean pageKnownByDefault = extractionMethod == null || !"APACHE_POI".equalsIgnoreCase(extractionMethod);

        for (TextFragment textFragment : fragments(text, pageKnownByDefault)) {
            String fragment = textFragment.text();
            Integer page = textFragment.page();
            String lower = fragment.toLowerCase(Locale.ROOT);

            extractContractHeader(fragment, page, scalars);

            Matcher paymentDays = DAYS.matcher(fragment);
            if (paymentDays.find() && isPaymentTermClause(fragment)) {
                putOnce(scalars, candidate(
                    ContractExtractionField.PAYMENT_DAYS,
                    paymentDays.group(1),
                    fragment,
                    page,
                    confidence("0.92"),
                    null
                ));
                TermDayType paymentDayType = termDayType(paymentDays.group(2));
                putOnce(scalars, candidate(
                    ContractExtractionField.PAYMENT_DAY_TYPE,
                    paymentDayType == null ? null : paymentDayType.name(),
                    fragment,
                    page,
                    paymentDayType == null ? null : confidence("0.93"),
                    null
                ));
                PaymentStartEvent event = paymentStartEvent(lower);
                putOnce(scalars, candidate(
                    ContractExtractionField.PAYMENT_START_EVENT,
                    event == null ? null : event.name(),
                    fragment,
                    page,
                    event == null ? null : confidence("0.88"),
                    null
                ));
                addClauseOnce(clauses, clause(fragment, page, ClauseType.PAYMENT_TERMS));
            }

            if (isPaymentPenaltyClause(lower)) {
                addClauseOnce(clauses, clause(fragment, page, ClauseType.PENALTY));
                boolean explicitArticle395 = containsAny(lower, "ст. 395", "статья 395", "статьи 395", "проценты за пользование чужими денежными средствами");
                boolean explicitAbsence = containsAny(
                    lower,
                    "условие о пене отсутствует",
                    "условия о пене отсутствуют",
                    "отсутствует специальное условие о пене",
                    "неустойка не предусмотрена",
                    "пеня не предусмотрена"
                );
                if (!explicitAbsence) {
                    Matcher rate = RATE.matcher(fragment);
                    if (explicitArticle395) {
                        putOnce(scalars, candidate(
                            ContractExtractionField.PENALTY_TYPE,
                            PenaltyType.ARTICLE_395.name(),
                            fragment,
                            page,
                            confidence("0.91"),
                            null
                        ));
                    } else if (rate.find()) {
                        putOnce(scalars, candidate(
                            ContractExtractionField.PENALTY_TYPE,
                            PenaltyType.CONTRACT_PENALTY.name(),
                            fragment,
                            page,
                            confidence("0.91"),
                            null
                        ));
                        putOnce(scalars, candidate(
                            ContractExtractionField.PENALTY_RATE,
                            rate.group(1).replace(',', '.'),
                            fragment,
                            page,
                            confidence("0.94"),
                            null
                        ));
                    }
                }
            }

            if (isClaimProcedureClause(lower)) {
                Matcher responseDays = DAYS.matcher(fragment);
                if (hasClaimResponseSignal(lower) && responseDays.find()) {
                    putOnce(scalars, candidate(
                        ContractExtractionField.CLAIM_RESPONSE_DAYS,
                        responseDays.group(1),
                        fragment,
                        page,
                        confidence("0.89"),
                        null
                    ));
                    TermDayType responseDayType = termDayType(responseDays.group(2));
                    putOnce(scalars, candidate(
                        ContractExtractionField.CLAIM_RESPONSE_DAY_TYPE,
                        responseDayType == null ? null : responseDayType.name(),
                        fragment,
                        page,
                        responseDayType == null ? null : confidence("0.90"),
                        null
                    ));
                }
                addClauseOnce(clauses, clause(fragment, page, ClauseType.CLAIM_PROCEDURE));
            }

            if (containsAny(lower, "подсудн", "арбитражный суд", "арбитражном суде")) {
                putOnce(scalars, candidate(
                    ContractExtractionField.JURISDICTION,
                    jurisdiction(fragment),
                    fragment,
                    page,
                    confidence("0.86"),
                    null
                ));
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

    private List<TextFragment> fragments(String text, boolean pageKnownByDefault) {
        List<TextFragment> result = new ArrayList<>();
        Integer page = pageKnownByDefault ? 1 : null;
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

    private boolean isPaymentTermClause(String fragment) {
        return PAYMENT_TERM_SIGNAL.matcher(fragment).find();
    }

    private boolean isPaymentPenaltyClause(String lower) {
        boolean explicitArticle395 = containsAny(lower, "ст. 395", "статья 395", "статьи 395", "проценты за пользование чужими денежными средствами");
        boolean hasPenalty = PENALTY_WORD.matcher(lower).find();
        boolean hasPayment = PAYMENT_WORD.matcher(lower).find();
        boolean hasDelay = PAYMENT_DELAY_WORD.matcher(lower).find() || containsAny(lower, "срок оплаты", "срока оплаты", "сроков оплаты");
        return explicitArticle395 || (hasPenalty && hasPayment && hasDelay);
    }

    private boolean isClaimProcedureClause(String lower) {
        if (!lower.contains("претензи")) return false;
        return containsAny(
            lower,
            "досуд",
            "до обращения",
            "урегулир",
            "рассматрива",
            "мотивированный",
            "ответ",
            "направля",
            "получившая претензи",
            "срок предъявления претензи"
        );
    }

    private boolean hasClaimResponseSignal(String lower) {
        return lower.contains("претензи") && containsAny(lower, "ответ", "рассматрива", "срок ответа");
    }

    private ContractExtractionCandidateRequest clause(String fragment, Integer page, ClauseType type) {
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
        Integer page,
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
        Integer page,
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
        // Combined anchors cannot be represented safely as a single event.
        if (containsAny(lower, "более поздн", "наступившей позднее", "которая наступит позднее")) return null;

        List<PaymentStartEvent> events = new ArrayList<>();
        if (lower.contains("реестр") && containsAny(
            lower, "включения рейса", "включения перевозки", "включен в реестр", "включения в реестр",
            "включения оказанных услуг в реестр", "включения услуги в реестр"
        )) {
            events.add(PaymentStartEvent.REGISTRY_INCLUDED);
        }
        if (containsAny(
            lower,
            "полного комплекта документов",
            "полного пакета документов",
            "комплекта закрывающих документов",
            "пакета закрывающих документов"
        ) && containsAny(lower, "получен", "предоставлен", "передан", "направлен", "представлен")) {
            events.add(PaymentStartEvent.DOCUMENT_PACKAGE_RECEIVED);
        }
        if (containsAny(lower, "ттн", "товарно-транспорт")
            || lower.matches("(?su).*транспортн\\p{L}*\\s+накладн\\p{L}*.*")) {
            events.add(PaymentStartEvent.TTN_SIGNED);
        }
        if (lower.matches("(?su).*подписан\\p{L}*(?:\\s+\\p{L}+){0,3}\\s+акт\\p{L}*.*")
            || containsAny(lower, "акт оказанных услуг")) {
            events.add(PaymentStartEvent.ACT_SIGNED);
        }
        if (containsAny(lower, "разгруз", "выгруз")) events.add(PaymentStartEvent.UNLOADING_DATE);
        if (INVOICE_WORD.matcher(lower).find() && !containsAny(lower, "комплект документов", "комплекта документов", "упд")) {
            events.add(PaymentStartEvent.INVOICE_DATE);
        }
        return events.stream().distinct().count() == 1 ? events.get(0) : null;
    }

    private TermDayType termDayType(String rawUnit) {
        if (rawUnit == null || rawUnit.isBlank()) return null;
        String unit = rawUnit.toLowerCase(Locale.ROOT);
        if (unit.startsWith("календарн")) return TermDayType.CALENDAR_DAYS;
        if (unit.startsWith("рабоч")) return TermDayType.WORKING_DAYS;
        if (unit.startsWith("банковск")) return TermDayType.BANKING_DAYS;
        return null;
    }

    private String jurisdiction(String fragment) {
        Matcher matcher = ARBITRATION_COURT.matcher(fragment);
        if (!matcher.find()) return fragment;
        String value = matcher.group(1).trim();
        value = value.replaceFirst("(?iu)^арбитражном\\s+суде", "Арбитражный суд");
        value = value.replaceFirst("(?iu)^арбитражный\\s+суд", "Арбитражный суд");
        return value.trim();
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
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private BigDecimal confidence(String value) {
        return new BigDecimal(value);
    }

    private record TextFragment(String text, Integer page) {}
}

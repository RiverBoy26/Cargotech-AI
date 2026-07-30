package ru.sber.cargotech.ai.guardrail;

import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Applies only deterministic, source-grounded corrections to parsed model output before guardrails.
 * It does not add new business facts, amounts, parties, dates or identifiers.
 */
public final class GeneratedOutputNormalizer {

    private static final String NOTIFICATION_TITLE =
            "Уведомление о составлении акта о непредоставлении транспортного средства";
    private static final String ACT_TITLE =
            "Акт о непредоставлении транспортного средства";

    private static final Pattern WRONG_NON_PROVISION_STEM = Pattern.compile("(?iu)непредставл");
    private static final DateTimeFormatter INPUT_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final Pattern PENALTY_PERIOD = Pattern.compile(
            "(?iu)(за\\s+период\\s+с\\s+)(\\d{2}\\.\\d{2}\\.\\d{4})(\\s*(?:г\\.)?\\s+по\\s+)(\\d{2}\\.\\d{2}\\.\\d{4})(\\s*(?:г\\.)?)"
    );
    private static final Pattern DISPATCHER_PARTY_ATTRIBUTION = Pattern.compile(
            "(?iu)(диспетчер\\p{L}*)(?:\\s+(?:ООО|ИП))?\\s*[«\"]?"
                    + "(?:перевозчик\\p{L}*|заказчик\\p{L}*|кредитор\\p{L}*|должник\\p{L}*|"
                    + "нашей\\s+организаци\\p{L}*|вашей\\s+организаци\\p{L}*|своей\\s+организаци\\p{L}*)[»\"]?"
    );

    private static final Pattern VEHICLE_PRESENTED = Pattern.compile(
            "(?iu)((?:транспортн\\p{L}*\\s+средств\\p{L}*|(?<![\\p{L}\\p{N}])тс(?![\\p{L}\\p{N}]))"
                    + "(?:\\s+\\p{L}+){0,5}\\s+)представлен"
    );

    private GeneratedOutputNormalizer() {
    }

    public static GenerateClaimResponse normalizeClaim(
            GenerateClaimRequest request,
            GenerateClaimResponse response
    ) {
        if (response == null || isEmptyMock(response)) return response;

        boolean loadingFailure = isLoadingFailure(request);
        String claimText = normalizeText(response.claimText(), request, loadingFailure);
        String summary = normalizeText(response.summaryForLawyer(), request, loadingFailure);
        if (loadingFailure) {
            claimText = neutralizeDispatcherAttribution(claimText, request);
            summary = neutralizeDispatcherAttribution(summary, request);
        } else {
            claimText = normalizePenaltyPeriod(claimText, request);
        }
        List<GenerateClaimResponse.Attachment> attachments = ensureRequiredClaimAttachments(
                normalizeAttachments(response.attachments(), request, loadingFailure),
                request
        );
        List<GenerateClaimResponse.UsedContractClause> clauses = immutable(response.usedContractClauses());
        List<GenerateClaimResponse.UsedLawArticle> laws = immutable(response.usedLawArticles());

        claimText = synchronizeCitations(claimText, request, clauses, laws, "Основания претензии");

        GenerateClaimResponse normalized = new GenerateClaimResponse(
                response.claimType(),
                claimText,
                summary,
                clauses,
                laws,
                response.backendCalculationUsed(),
                attachments,
                normalizeStrings(response.warnings(), request, loadingFailure),
                response.manualReviewRequired()
        );
        return normalized.equals(response) ? response : normalized;
    }

    public static GenerateDocumentResponse normalizeDocument(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            GenerateClaimResponse.DocumentType expectedType
    ) {
        if (response == null || isEmptyMock(response)) return response;

        boolean loadingFailure = isLoadingFailure(request);
        String title = canonicalTitle(expectedType, response.documentTitle());
        title = normalizeText(title, request, loadingFailure);
        String documentText = normalizeText(response.documentText(), request, loadingFailure);
        String summary = normalizeText(response.summaryForLawyer(), request, loadingFailure);
        if (loadingFailure) {
            documentText = neutralizeDispatcherAttribution(documentText, request);
            summary = neutralizeDispatcherAttribution(summary, request);
        }
        if (expectedType == GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT) {
            documentText = ensureActIdentity(documentText, request);
        }
        List<GenerateClaimResponse.Attachment> attachments = ensureRequiredDocumentAttachments(
                normalizeAttachments(response.attachments(), request, loadingFailure),
                request,
                expectedType
        );
        List<GenerateClaimResponse.UsedContractClause> clauses = immutable(response.usedContractClauses());
        List<GenerateClaimResponse.UsedLawArticle> laws = immutable(response.usedLawArticles());

        documentText = synchronizeCitations(
                documentText,
                request,
                clauses,
                laws,
                expectedType == GenerateClaimResponse.DocumentType.NOTIFICATION
                        ? "Основание уведомления"
                        : "Основание составления акта"
        );

        GenerateDocumentResponse normalized = new GenerateDocumentResponse(
                expectedType == null ? response.documentType() : expectedType,
                title,
                documentText,
                summary,
                clauses,
                laws,
                attachments,
                normalizeStrings(response.warnings(), request, loadingFailure),
                response.manualReviewRequired()
        );
        return normalized.equals(response) ? response : normalized;
    }

    private static String synchronizeCitations(
            String text,
            GenerateClaimRequest request,
            List<GenerateClaimResponse.UsedContractClause> clauses,
            List<GenerateClaimResponse.UsedLawArticle> laws,
            String heading
    ) {
        if (!hasText(text) || request == null) return text;

        Set<String> missing = new LinkedHashSet<>();
        for (GenerateClaimResponse.UsedContractClause used : clauses) {
            if (!isValidContractCitation(request, used)) continue;
            if (!CitationTextMatcher.containsContractClauseReference(text, used.clauseNumber())) {
                missing.add("п. " + used.clauseNumber() + " договора");
            }
        }
        for (GenerateClaimResponse.UsedLawArticle used : laws) {
            if (!isValidLawCitation(request, used)) continue;
            if (!CitationTextMatcher.containsLegalReference(text, used.lawCode(), used.article())) {
                missing.add(formatLawReference(used));
            }
        }

        if (missing.isEmpty()) return text;
        String suffix = heading + ": " + String.join("; ", missing) + ".";
        String trimmed = text.stripTrailing();
        return trimmed + (trimmed.endsWith(".") ? "\n\n" : ".\n\n") + suffix;
    }

    private static boolean isValidContractCitation(
            GenerateClaimRequest request,
            GenerateClaimResponse.UsedContractClause used
    ) {
        if (used == null || !hasText(used.chunkId()) || !hasText(used.clauseNumber())) return false;
        for (GenerateClaimRequest.ContractContextChunk source : immutable(request.contractContext())) {
            if (source == null) continue;
            if (Objects.equals(source.chunkId(), used.chunkId())
                    && same(source.clauseNumber(), used.clauseNumber())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isValidLawCitation(
            GenerateClaimRequest request,
            GenerateClaimResponse.UsedLawArticle used
    ) {
        if (used == null || !hasText(used.chunkId()) || !hasText(used.lawCode()) || !hasText(used.article())) {
            return false;
        }
        for (GenerateClaimRequest.LegalContextItem source : immutable(request.legalContext())) {
            if (source == null) continue;
            if (Objects.equals(source.chunkId(), used.chunkId())
                    && same(source.lawCode(), used.lawCode())
                    && same(source.article(), used.article())) {
                return true;
            }
        }
        return false;
    }

    private static String formatLawReference(GenerateClaimResponse.UsedLawArticle used) {
        String lawCode = used.lawCode().trim();
        String article = used.article().trim();
        String normalizedCode = normalizeKey(lawCode);
        String normalizedArticle = normalizeKey(article);

        if (normalizedCode.contains("правил") || normalizedArticle.contains("пункт")) {
            String number = article.replaceAll("[^0-9.\\-]", "");
            return "п. " + (number.isBlank() ? article : number) + " " + lawCode;
        }
        String number = article.replaceAll("[^0-9.\\-]", "");
        return "ст. " + (number.isBlank() ? article : number) + " " + lawCode;
    }

    private static String canonicalTitle(
            GenerateClaimResponse.DocumentType type,
            String current
    ) {
        if (type == GenerateClaimResponse.DocumentType.NOTIFICATION) return NOTIFICATION_TITLE;
        if (type == GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT) return ACT_TITLE;
        return current;
    }

    private static String normalizeText(
            String value,
            GenerateClaimRequest request,
            boolean loadingFailure
    ) {
        if (value == null) return null;
        String normalized = canonicalizeIdentifiers(value, request);
        if (loadingFailure) {
            normalized = replaceStemPreservingCase(normalized, WRONG_NON_PROVISION_STEM, "непредоставл");
            normalized = normalizeVehiclePresented(normalized);
        }
        return normalized;
    }

    private static String normalizeVehiclePresented(String input) {
        Matcher matcher = VEHICLE_PRESENTED.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String stem = matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(stem + "предоставлен"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String replaceStemPreservingCase(String input, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String matched = matcher.group();
            String actualReplacement = replacement;
            if (matched != null && !matched.isEmpty() && Character.isUpperCase(matched.codePointAt(0))) {
                int first = actualReplacement.codePointAt(0);
                actualReplacement = new StringBuilder(actualReplacement)
                        .replace(0, Character.charCount(first), new String(Character.toChars(Character.toUpperCase(first))))
                        .toString();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(actualReplacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String canonicalizeIdentifiers(String value, GenerateClaimRequest request) {
        if (!hasText(value) || request == null || request.caseFacts() == null) return value;

        List<String> identifiers = expectedIdentifiers(request);
        identifiers.sort(Comparator.comparingInt(String::length).reversed());
        String result = value;
        for (String identifier : identifiers) {
            Pattern pattern = identifierPattern(identifier);
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(identifier));
        }
        return result;
    }

    private static List<String> expectedIdentifiers(GenerateClaimRequest request) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        GenerateClaimRequest.CaseFacts facts = request.caseFacts();
        if (facts == null) return new ArrayList<>();
        if (facts.contract() != null) add(identifiers, facts.contract().contractNumber());
        if (facts.shipment() != null) {
            add(identifiers, facts.shipment().orderNumber());
            add(identifiers, facts.shipment().actNumber());
            add(identifiers, facts.shipment().ttnNumber());
            add(identifiers, facts.shipment().invoiceNumber());
        }
        return new ArrayList<>(identifiers);
    }

    private static Pattern identifierPattern(String identifier) {
        StringBuilder regex = new StringBuilder("(?iu)(?<![\\p{L}\\p{N}])");
        for (int offset = 0; offset < identifier.length();) {
            int cp = identifier.codePointAt(offset);
            offset += Character.charCount(cp);
            regex.append(identifierCharPattern(cp));
        }
        regex.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(regex.toString());
    }

    private static String identifierCharPattern(int codePoint) {
        char value = Character.toUpperCase((char) codePoint);
        return switch (value) {
            case 'A', 'А' -> "[AАaа]";
            case 'B', 'В' -> "[BВbв]";
            case 'C', 'С' -> "[CСcс]";
            case 'E', 'Е' -> "[EЕeе]";
            case 'H', 'Н' -> "[HНhн]";
            case 'K', 'К' -> "[KКkк]";
            case 'M', 'М' -> "[MМmм]";
            case 'O', 'О' -> "[OОoо]";
            case 'P', 'Р' -> "[PРpр]";
            case 'T', 'Т' -> "[TТtт]";
            case 'X', 'Х' -> "[XХxх]";
            case 'Y', 'У' -> "[YУyу]";
            case '-', '‐', '‑', '‒', '–', '—', '−' -> "[-‐‑‒–—−]";
            default -> Character.isWhitespace(codePoint) ? "\\s+" : Pattern.quote(new String(Character.toChars(codePoint)));
        };
    }

    private static List<GenerateClaimResponse.Attachment> normalizeAttachments(
            List<GenerateClaimResponse.Attachment> attachments,
            GenerateClaimRequest request,
            boolean loadingFailure
    ) {
        List<GenerateClaimResponse.Attachment> result = new ArrayList<>();
        if (attachments == null) return result;
        for (GenerateClaimResponse.Attachment attachment : attachments) {
            if (attachment == null) {
                result.add(null);
                continue;
            }
            result.add(new GenerateClaimResponse.Attachment(
                    attachment.documentType(),
                    normalizeText(attachment.documentName(), request, loadingFailure),
                    attachment.required()
            ));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<GenerateClaimResponse.Attachment> ensureRequiredClaimAttachments(
            List<GenerateClaimResponse.Attachment> current,
            GenerateClaimRequest request
    ) {
        if (request == null || request.caseFacts() == null) return immutable(current);
        GenerateClaimRequest.CaseFacts facts = request.caseFacts();
        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();
        LinkedHashMap<GenerateClaimResponse.DocumentType, GenerateClaimResponse.Attachment> result = attachmentMap(current);

        if (facts.contract() != null && hasText(facts.contract().contractNumber())) {
            upsertRequired(result, GenerateClaimResponse.DocumentType.CONTRACT,
                    documentName("Договор №", facts.contract().contractNumber(), facts.contract().contractDate()));
        }
        if (shipment != null) {
            if (facts.claimType() == GenerateClaimRequest.ClaimType.LOADING_FAILURE && hasText(shipment.orderNumber())) {
                upsertRequired(result, GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                        "Транспортная заявка №" + shipment.orderNumber());
            }
            if (facts.claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY
                    && (hasText(shipment.actNumber()) || hasText(shipment.actDate()))) {
                upsertRequired(result, GenerateClaimResponse.DocumentType.ACT,
                        documentName("Акт №", shipment.actNumber(), shipment.actDate()));
            }
            if (facts.claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY && hasText(shipment.ttnNumber())) {
                upsertRequired(result, GenerateClaimResponse.DocumentType.TTN, "ТТН №" + shipment.ttnNumber());
            }
            if (facts.claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY && hasText(shipment.invoiceNumber())) {
                upsertRequired(result, GenerateClaimResponse.DocumentType.INVOICE, "Счёт №" + shipment.invoiceNumber());
            }
            if (facts.claimType() == GenerateClaimRequest.ClaimType.LOADING_FAILURE
                    && Boolean.TRUE.equals(shipment.failureConfirmedByDispatcher())
                    && (hasText(shipment.actNumber()) || hasText(shipment.actDate()))) {
                upsertRequired(result, GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT,
                        documentName("Акт о непредоставлении транспортного средства №",
                                shipment.actNumber(), shipment.actDate()));
            }
        }
        if (request.backendCalculation() != null) {
            upsertRequired(result, GenerateClaimResponse.DocumentType.CALCULATION,
                    "Расчёт задолженности и неустойки");
        }
        return orderedAttachments(result);
    }

    private static List<GenerateClaimResponse.Attachment> ensureRequiredDocumentAttachments(
            List<GenerateClaimResponse.Attachment> current,
            GenerateClaimRequest request,
            GenerateClaimResponse.DocumentType expectedType
    ) {
        if (request == null || request.caseFacts() == null) return immutable(current);
        LinkedHashMap<GenerateClaimResponse.DocumentType, GenerateClaimResponse.Attachment> result = attachmentMap(current);
        GenerateClaimRequest.ShipmentFacts shipment = request.caseFacts().shipment();
        if (shipment != null && hasText(shipment.orderNumber())) {
            upsertRequired(result, GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                    "Транспортная заявка №" + shipment.orderNumber());
        }
        if (request.caseFacts().contract() != null && hasText(request.caseFacts().contract().contractNumber())) {
            upsertRequired(result, GenerateClaimResponse.DocumentType.CONTRACT,
                    documentName("Договор №", request.caseFacts().contract().contractNumber(),
                            request.caseFacts().contract().contractDate()));
        }
        return orderedAttachments(result);
    }

    private static LinkedHashMap<GenerateClaimResponse.DocumentType, GenerateClaimResponse.Attachment> attachmentMap(
            List<GenerateClaimResponse.Attachment> attachments
    ) {
        LinkedHashMap<GenerateClaimResponse.DocumentType, GenerateClaimResponse.Attachment> result = new LinkedHashMap<>();
        for (GenerateClaimResponse.Attachment attachment : immutable(attachments)) {
            if (attachment == null || attachment.documentType() == null) continue;
            GenerateClaimResponse.Attachment previous = result.get(attachment.documentType());
            if (previous == null || (!Boolean.TRUE.equals(previous.required()) && Boolean.TRUE.equals(attachment.required()))) {
                result.put(attachment.documentType(), attachment);
            }
        }
        return result;
    }

    private static void upsertRequired(
            LinkedHashMap<GenerateClaimResponse.DocumentType, GenerateClaimResponse.Attachment> target,
            GenerateClaimResponse.DocumentType type,
            String canonicalName
    ) {
        GenerateClaimResponse.Attachment existing = target.get(type);
        String name = existing != null && hasText(existing.documentName()) ? existing.documentName() : canonicalName;
        target.put(type, new GenerateClaimResponse.Attachment(type, name, true));
    }

    private static List<GenerateClaimResponse.Attachment> orderedAttachments(
            LinkedHashMap<GenerateClaimResponse.DocumentType, GenerateClaimResponse.Attachment> values
    ) {
        List<GenerateClaimResponse.DocumentType> order = List.of(
                GenerateClaimResponse.DocumentType.CONTRACT,
                GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                GenerateClaimResponse.DocumentType.ACT,
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT,
                GenerateClaimResponse.DocumentType.TTN,
                GenerateClaimResponse.DocumentType.INVOICE,
                GenerateClaimResponse.DocumentType.CALCULATION,
                GenerateClaimResponse.DocumentType.PAYMENT_EXTRACT,
                GenerateClaimResponse.DocumentType.NOTIFICATION,
                GenerateClaimResponse.DocumentType.OTHER
        );
        List<GenerateClaimResponse.Attachment> result = new ArrayList<>();
        for (GenerateClaimResponse.DocumentType type : order) {
            GenerateClaimResponse.Attachment attachment = values.get(type);
            if (attachment != null) result.add(attachment);
        }
        for (GenerateClaimResponse.Attachment attachment : values.values()) {
            if (!result.contains(attachment)) result.add(attachment);
        }
        return Collections.unmodifiableList(result);
    }

    private static String documentName(String prefix, String number, String date) {
        StringBuilder result = new StringBuilder(prefix);
        if (hasText(number)) result.append(number.trim());
        if (hasText(date)) result.append(" от ").append(date.trim());
        return result.toString().trim();
    }

    private static String normalizePenaltyPeriod(String text, GenerateClaimRequest request) {
        if (!hasText(text) || request == null || request.caseFacts() == null
                || request.caseFacts().payment() == null || request.backendCalculation() == null) return text;
        String dueValue = request.caseFacts().payment().paymentDueDate();
        String claimValue = request.caseFacts().claimDate();
        Integer overdueDays = request.backendCalculation().overdueDays();
        if (!hasText(dueValue) || !hasText(claimValue) || overdueDays == null || overdueDays <= 0) return text;

        try {
            LocalDate due = LocalDate.parse(dueValue, INPUT_DATE);
            LocalDate claim = LocalDate.parse(claimValue, INPUT_DATE);
            LocalDate expectedStart = due.plusDays(1);
            long inclusiveDays = ChronoUnit.DAYS.between(expectedStart, claim) + 1;
            if (inclusiveDays != overdueDays) return text;

            String expectedStartText = expectedStart.format(INPUT_DATE);
            Matcher matcher = PENALTY_PERIOD.matcher(text);
            StringBuffer output = new StringBuffer();
            boolean foundPeriod = false;
            while (matcher.find()) {
                foundPeriod = true;
                String end = matcher.group(4);
                if (claimValue.equals(end)) {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(
                            matcher.group(1) + expectedStartText + matcher.group(3) + claimValue + matcher.group(5)
                    ));
                } else {
                    matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group()));
                }
            }
            matcher.appendTail(output);
            String normalized = output.toString();
            if (!foundPeriod) {
                String suffix = "Период расчёта неустойки: с " + expectedStartText + " по " + claimValue
                        + " включительно; количество дней просрочки по расчёту бэкенда — " + overdueDays + ".";
                String trimmed = normalized.stripTrailing();
                normalized = trimmed + (trimmed.endsWith(".") ? "\n\n" : ".\n\n") + suffix;
            }
            return normalized;
        } catch (DateTimeParseException ignored) {
            return text;
        }
    }

    private static String neutralizeDispatcherAttribution(String text, GenerateClaimRequest request) {
        if (!hasText(text)) return text;
        String result = DISPATCHER_PARTY_ATTRIBUTION.matcher(text).replaceAll("$1");
        if (request != null && request.caseFacts() != null) {
            result = removeKnownPartyAfterDispatcher(result, request.caseFacts().creditor());
            result = removeKnownPartyAfterDispatcher(result, request.caseFacts().debtor());
        }
        return result;
    }

    private static String removeKnownPartyAfterDispatcher(String text, GenerateClaimRequest.Party party) {
        if (party == null || !hasText(party.name())) return text;
        Pattern pattern = Pattern.compile(
                "(?iu)(диспетчер\\p{L}*)\\s+[«\\\"]?" + Pattern.quote(party.name().trim()) + "[»\\\"]?"
        );
        return pattern.matcher(text).replaceAll("$1");
    }

    private static String ensureActIdentity(String text, GenerateClaimRequest request) {
        if (!hasText(text) || request == null || request.caseFacts() == null
                || request.caseFacts().shipment() == null) return text;
        GenerateClaimRequest.ShipmentFacts shipment = request.caseFacts().shipment();
        String number = shipment.actNumber();
        String date = shipment.actDate();
        StringBuilder prefix = new StringBuilder();
        String first = text.length() > 300 ? text.substring(0, 300) : text;
        if (hasText(number) && !normalizeKey(first).contains(normalizeKey("акт №" + number))) {
            prefix.append("Акт №").append(number).append(" о непредоставлении транспортного средства\n");
        }
        if (hasText(date) && !Pattern.compile(
                "(?iu)дата\\s+составлени\\p{L}*\\s+акта\\s*:?\\s*" + Pattern.quote(date)
        ).matcher(text).find()) {
            prefix.append("Дата составления акта: ").append(date).append(".\n");
        }
        if (prefix.isEmpty()) return text;
        return prefix.append("\n").append(text.stripLeading()).toString();
    }

    private static List<String> normalizeStrings(
            List<String> values,
            GenerateClaimRequest request,
            boolean loadingFailure
    ) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(normalizeText(value, request, loadingFailure));
        return Collections.unmodifiableList(result);
    }

    private static boolean isLoadingFailure(GenerateClaimRequest request) {
        return request != null
                && request.caseFacts() != null
                && request.caseFacts().claimType() == GenerateClaimRequest.ClaimType.LOADING_FAILURE;
    }

    private static boolean isEmptyMock(GenerateClaimResponse response) {
        return response.claimType() == null
                && response.claimText() == null
                && response.summaryForLawyer() == null
                && response.usedContractClauses() == null
                && response.usedLawArticles() == null
                && response.backendCalculationUsed() == null
                && response.attachments() == null
                && response.warnings() == null
                && response.manualReviewRequired() == null;
    }

    private static boolean isEmptyMock(GenerateDocumentResponse response) {
        return response.documentType() == null
                && response.documentTitle() == null
                && response.documentText() == null
                && response.summaryForLawyer() == null
                && response.usedContractClauses() == null
                && response.usedLawArticles() == null
                && response.attachments() == null
                && response.warnings() == null
                && response.manualReviewRequired() == null;
    }

    private static boolean same(String left, String right) {
        return normalizeKey(left).equals(normalizeKey(right));
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('—', '-')
                .replace('–', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void add(Set<String> target, String value) {
        if (hasText(value)) target.add(value.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}

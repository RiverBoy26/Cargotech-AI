package ru.sber.cargotech.ai.document.guardrail;

import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailDecision;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;
import ru.sber.cargotech.ai.guardrail.CitationTextMatcher;

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
    private static final Pattern LEGAL_REFERENCE = Pattern.compile(
            "(?iu)(?:ст(?:атья|\\.)?\\s*\\d+(?:[.\\-]\\d+)?\\s*(?:гк|апк|гпк|тк|нк)\\s*рф"
                    + "|пункт\\s*\\d+(?:[.\\-]\\d+)?\\s+правил"
                    + "|правил\\s*(?:n|№)\\s*\\d+)"
    );
    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)"
    );
    private static final Pattern UNSUPPORTED_REPRESENTATIVE_LANGUAGE = Pattern.compile(
            "(?iu)(?:мы\\s*,?\\s*)?нижеподписавш\\p{L}*|представител\\p{L}*\\s+(?:сторон|кредитора|должника|заказчика|перевозчика)"
    );
    private static final Pattern UNSUPPORTED_DISPATCHER_ATTRIBUTION = Pattern.compile(
            "(?iu)диспетчер\\p{L}*\\s+(?:нашей|вашей|своей|кредитор\\p{L}*|должник\\p{L}*|заказчик\\p{L}*|перевозчик\\p{L}*|ооо|ип)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern CONFIRMATION_SUBSTITUTION = Pattern.compile(
            "(?iu)(?:неподтверждени\\p{L}*|отсутстви\\p{L}*\\s+подтверждени\\p{L}*)\\s+(?:факт\\p{L}*\\s+)?(?:подач\\p{L}*|предоставлени\\p{L}*)"
                    + "|(?:подач\\p{L}*|предоставлени\\p{L}*)\\s+(?:транспортн\\p{L}*\\s+средств\\p{L}*\\s+)?не\\s+подтвержден\\p{L}*"
    );
    private static final Pattern POSITIVE_VEHICLE_PROVISION = Pattern.compile(
            "(?iu)(?<!не\\s)(?<!не\\sбыло\\s)(?:транспортн\\p{L}*\\s+средств\\p{L}*|тс)\\s+(?:было\\s+)?(?:предоставлен\\p{L}*|подан\\p{L}*|прибыл\\p{L}*)(?!\\s+не\\s+был\\p{L}*)"
    );
    private static final Pattern UNSUPPORTED_VEHICLE_IDENTITY = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:марк(?:а|и)|модел\\p{L}*|госномер\\p{L}*|государственн\\p{L}*\\s+регистрационн\\p{L}*\\s+номер\\p{L}*|регистрационн\\p{L}*\\s+номер\\p{L}*|водител\\p{L}*)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern UNSUPPORTED_INCIDENT_CIRCUMSTANCE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:поломк\\p{L}*|неисправност\\p{L}*|дтп|пробк\\p{L}*|опоздал\\p{L}*|покинул\\p{L}*|не\\s+дождал\\p{L}*|отказал\\p{L}*)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern EXPLICIT_NON_PROVISION = Pattern.compile(
            "(?iu)(?:непредоставлени\\p{L}*\\s+(?:транспортн\\p{L}*\\s+средств\\p{L}*|тс)"
                    + "|срыв\\p{L}*\\s+погрузк\\p{L}*"
                    + "|(?:транспортн\\p{L}*\\s+средств\\p{L}*|тс)\\s+"
                    + "(?:не\\s+(?:был\\p{L}*\\s+)?(?:предоставлен\\p{L}*|подан\\p{L}*|прибыл\\p{L}*)"
                    + "|(?:предоставлен\\p{L}*|подан\\p{L}*)\\s+не\\s+был\\p{L}*))"
    );
    private static final Pattern BILATERAL_ACT_LANGUAGE = Pattern.compile(
            "(?iu)(?:сторон\\p{L}*\\s+составил\\p{L}*|совместн\\p{L}*\\s+акт|двусторонн\\p{L}*\\s+акт|составили\\s+настоящий\\s+акт)"
    );
    private static final Pattern COMPLETED_ACT_LANGUAGE = Pattern.compile(
            "(?iu)(?:акт\\s+(?:уже\\s+)?составлен\\p{L}*|составленн\\p{L}*\\s+акт|акт\\s+прилагает\\p{L}*)"
    );
    private static final Pattern UNSUPPORTED_APPEARANCE_INVITATION = Pattern.compile(
            "(?iu)(?:просим\\s+явиться|направить\\s+представител\\p{L}*|присутствовать\\s+при\\s+составлении|для\\s+составления\\s+совместн\\p{L}*\\s+акта)"
    );
    private static final Set<String> ADDRESS_STOP_WORDS = Set.of(
            "г", "город", "по", "адрес", "адресу", "погрузк", "мест",
            "на", "в", "во", "у", "д", "дом", "корп", "корпус",
            "стр", "строен"
    );
    private static final int ADDRESS_TOKEN_WINDOW = 20;
    private static final int ADDRESS_NUMBER_MAX_DISTANCE = 5;
    private static final int TIME_WINDOW_MAX_DISTANCE = 120;
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
            validateText(request, response, expectedType, errors, warnings);
            validateClauses(request, response, errors, warnings);
            validateLawArticles(request, response, errors, warnings);
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
            List<String> errors,
            List<String> warnings
    ) {
        String title = response.documentTitle() == null ? "" : response.documentTitle();
        String text = response.documentText() == null ? "" : response.documentText();
        String summary = response.summaryForLawyer() == null ? "" : response.summaryForLawyer();
        String narrative = title + "\n" + text + "\n" + summary;
        String normalized = normalize(text);
        String normalizedTitle = normalize(title);

        String expectedTitle = expectedType == GenerateClaimResponse.DocumentType.NOTIFICATION
                ? "Уведомление о составлении акта о непредоставлении транспортного средства"
                : "Акт о непредоставлении транспортного средства";
        if (!normalizedTitle.equals(normalize(expectedTitle))) {
            errors.add("document_title must be exactly: " + expectedTitle);
        }

        if (request != null && request.caseFacts() != null) {
            GenerateClaimRequest.CaseFacts facts = request.caseFacts();
            require(normalized, facts.creditor() == null ? null : facts.creditor().name(), "creditor.name", errors);
            require(normalized, facts.debtor() == null ? null : facts.debtor().name(), "debtor.name", errors);
            if (expectedType == GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT) {
                require(normalized, facts.creditor() == null ? null : facts.creditor().inn(), "creditor.inn", errors);
                require(normalized, facts.debtor() == null ? null : facts.debtor().inn(), "debtor.inn", errors);
            }
            validateUnknownInns(facts, narrative, errors);
            if (facts.contract() != null) {
                require(normalized, facts.contract().contractNumber(), "contract.contract_number", errors);
            }
            if (facts.shipment() != null) {
                GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();
                requireOrderReference(normalized, shipment.orderNumber(), response.attachments(), errors);
                requireDate(text, shipment.loadingDate(), "shipment.loading_date", errors);
                requireAddress(text, shipment.loadingAddress(), "shipment.loading_address", errors);
                requireTimeWindow(text, shipment.loadingTimeWindow(), "shipment.loading_time_window", errors);
                require(normalized, shipment.route(), "shipment.route", errors);
                requireVehicleRequirements(text, shipment.vehicleRequirements(), errors);
                validateLoadingFailureSemantics(narrative, shipment, errors, warnings);

                if (expectedType == GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT) {
                    require(normalized, shipment.actNumber(), "shipment.act_number", errors);
                    requireDate(text,
                            hasText(shipment.actDate()) ? shipment.actDate() : facts.claimDate(),
                            hasText(shipment.actDate()) ? "shipment.act_date" : "case_facts.claim_date",
                            errors);
                    requireActCreator(text, facts.creditor(), errors);
                } else {
                    requireDate(text, facts.claimDate(), "case_facts.claim_date", errors);
                    if (hasText(shipment.actNumber())
                            && normalized.contains(normalize(shipment.actNumber()))) {
                        errors.add("Notification must not present shipment.act_number as an already created act");
                    }
                    if (!containsFutureActIntent(text)) {
                        errors.add("Notification does not clearly state that the act will be prepared in the future");
                    }
                    if (COMPLETED_ACT_LANGUAGE.matcher(narrative).find()) {
                        errors.add("Notification presents the act as already completed");
                    }
                    if (UNSUPPORTED_APPEARANCE_INVITATION.matcher(narrative).find()) {
                        errors.add("Notification invents representative attendance instructions without planned act schedule in case_facts");
                    }
                }
            }
        }

        if (UNSUPPORTED_REPRESENTATIVE_LANGUAGE.matcher(narrative).find()
                || BILATERAL_ACT_LANGUAGE.matcher(narrative).find()) {
            errors.add("Document invents representatives or bilateral signing participants not present in case_facts");
        }
        if (UNSUPPORTED_DISPATCHER_ATTRIBUTION.matcher(narrative).find()) {
            errors.add("Document attributes dispatcher confirmation to a party not identified in case_facts");
        }
        if (CitationTextMatcher.containsUnsupportedInstanceQualifier(
                title,
                text,
                summary,
                attachmentNames(response.attachments())
        )) {
            errors.add("Document invents document instance type (original/copy) absent from case_facts");
        }

        if (RUB_AMOUNT.matcher(narrative).find()) {
            errors.add("Fixation document contains a monetary amount");
        }

        String normalizedNarrative = normalize(narrative);
        for (String phrase : List.of("обратиться в суд", "в судебном порядке", "исковое заявление", "подать иск", "арбитражный суд")) {
            if (normalizedNarrative.contains(normalize(phrase))) errors.add("Document contains forbidden court phrase: " + phrase);
        }
        for (String phrase : List.of("требуем оплатить", "уплатить штраф", "оплатить неустойку", "взыскать")) {
            if (normalizedNarrative.contains(normalize(phrase))) errors.add("Fixation document contains monetary demand: " + phrase);
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
            } else if (!CitationTextMatcher.containsContractClauseReference(
                    response.documentText(), used.clauseNumber())) {
                errors.add("Document declared contract clause not mentioned in document_text: "
                        + used.clauseNumber());
            }
        }
    }

    private void validateLawArticles(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        Map<String, GenerateClaimRequest.LegalContextItem> allowed = new HashMap<>();
        if (request != null) {
            for (GenerateClaimRequest.LegalContextItem item : safeList(request.legalContext())) {
                if (item != null && hasText(item.chunkId())) {
                    allowed.put(item.chunkId(), item);
                }
            }
        }

        List<GenerateClaimResponse.UsedLawArticle> used = safeList(response.usedLawArticles());
        String narrative = (response.documentText() == null ? "" : response.documentText())
                + "\n"
                + (response.summaryForLawyer() == null ? "" : response.summaryForLawyer());

        if (LEGAL_REFERENCE.matcher(narrative).find() && used.isEmpty()) {
            errors.add("Document contains a legal reference without used_law_articles traceability");
        }
        for (GenerateClaimResponse.UsedLawArticle article : used) {
            if (article == null || !hasText(article.chunkId())) {
                errors.add("Document legal citation must contain chunk_id");
                continue;
            }
            GenerateClaimRequest.LegalContextItem source = allowed.get(article.chunkId());
            if (source == null) {
                errors.add("Document used unknown legal chunk_id: " + article.chunkId());
                continue;
            }
            if (!Objects.equals(normalize(source.lawCode()), normalize(article.lawCode()))
                    || !Objects.equals(normalize(source.article()), normalize(article.article()))) {
                errors.add("Document legal chunk_id does not match law_code/article: " + article.chunkId());
            } else if (!CitationTextMatcher.containsLegalReference(
                    response.documentText(), article.lawCode(), article.article())) {
                errors.add("Document declared law article not mentioned in document_text: "
                        + article.lawCode() + " " + article.article());
            }
        }
        if (!used.isEmpty() && allowed.isEmpty()) {
            warnings.add("Document returned legal citations while legal_context is empty");
        }
    }

    private void validateAttachments(
            GenerateClaimRequest request,
            GenerateDocumentResponse response,
            List<String> errors,
            List<String> warnings
    ) {
        String expectedOrderNumber = request != null && request.caseFacts() != null
                && request.caseFacts().shipment() != null
                ? request.caseFacts().shipment().orderNumber()
                : null;
        boolean orderExists = hasText(expectedOrderNumber);

        for (GenerateClaimResponse.Attachment attachment : safeList(response.attachments())) {
            if (attachment == null || attachment.documentType() == null) {
                errors.add("attachments contains item without document_type");
                continue;
            }
            if (attachment.documentType() != GenerateClaimResponse.DocumentType.TRANSPORT_ORDER || !orderExists) {
                errors.add("Document added unsupported attachment: " + attachment.documentType());
                continue;
            }
            if (!hasText(attachment.documentName())) {
                errors.add("TRANSPORT_ORDER attachment must contain document_name");
            } else if (!normalize(attachment.documentName()).contains(normalize(expectedOrderNumber))) {
                errors.add("TRANSPORT_ORDER attachment does not match shipment.order_number: " + expectedOrderNumber);
            }
        }
        if (response.attachments() == null) warnings.add("response.attachments is null");
    }

    private String attachmentNames(List<GenerateClaimResponse.Attachment> attachments) {
        StringBuilder result = new StringBuilder();
        for (GenerateClaimResponse.Attachment attachment : safeList(attachments)) {
            if (attachment != null && hasText(attachment.documentName())) {
                if (!result.isEmpty()) result.append('\n');
                result.append(attachment.documentName());
            }
        }
        return result.toString();
    }

    private void requireOrderReference(
            String normalizedText,
            String expectedOrderNumber,
            List<GenerateClaimResponse.Attachment> attachments,
            List<String> errors
    ) {
        if (!hasText(expectedOrderNumber)) return;
        if (normalizedText.contains(normalize(expectedOrderNumber))) return;

        for (GenerateClaimResponse.Attachment attachment : safeList(attachments)) {
            if (attachment != null
                    && attachment.documentType() == GenerateClaimResponse.DocumentType.TRANSPORT_ORDER
                    && hasText(attachment.documentName())
                    && normalize(attachment.documentName()).contains(normalize(expectedOrderNumber))) {
                return;
            }
        }
        errors.add("document_text or TRANSPORT_ORDER attachment does not contain expected shipment.order_number: "
                + expectedOrderNumber);
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


    private void validateLoadingFailureSemantics(
            String narrative,
            GenerateClaimRequest.ShipmentFacts shipment,
            List<String> errors,
            List<String> warnings
    ) {
        String normalized = normalize(narrative);

        if (CONFIRMATION_SUBSTITUTION.matcher(narrative).find()) {
            errors.add("Document replaces confirmed vehicle non-provision with absence of confirmation");
        }
        if (!containsExplicitNonProvision(narrative)) {
            errors.add("Document does not clearly state confirmed vehicle non-provision");
        }
        if (POSITIVE_VEHICLE_PROVISION.matcher(narrative).find()) {
            errors.add("Document contradicts case_facts by asserting that the vehicle was provided or arrived");
        }

        String allowedVehicleFacts = normalize(shipment == null ? null : shipment.vehicleRequirements());
        Matcher identityMatcher = UNSUPPORTED_VEHICLE_IDENTITY.matcher(narrative);
        while (identityMatcher.find()) {
            String found = normalize(identityMatcher.group());
            if (!allowedVehicleFacts.contains(found)) {
                errors.add("Document invents unsupported vehicle identity detail: " + identityMatcher.group());
                break;
            }
        }
        if (UNSUPPORTED_INCIDENT_CIRCUMSTANCE.matcher(narrative).find()) {
            errors.add("Document invents a cause or incident circumstance absent from case_facts");
        }
        if (normalized.contains("непредставлен")) {
            warnings.add("Use the legal term «непредоставление транспортного средства», not «непредставление»");
        }
    }

    private boolean containsExplicitNonProvision(String text) {
        return hasText(text) && EXPLICIT_NON_PROVISION.matcher(text).find();
    }

    private void requireVehicleRequirements(String text, String expected, List<String> errors) {
        if (!hasText(expected)) return;
        Set<String> required = new LinkedHashSet<>(semanticTerms(expected));
        Set<String> actual = new HashSet<>(semanticTerms(text));
        if (!actual.containsAll(required)) {
            errors.add("document_text does not contain expected shipment.vehicle_requirements: " + expected);
        }
    }

    private List<String> semanticTerms(String value) {
        if (!hasText(value)) return List.of();
        List<String> result = new ArrayList<>();
        for (String token : value.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank()) continue;
            if (Set.of("т", "тонна", "тонны", "тонн").contains(token)) {
                result.add("тонн");
            } else if (token.length() > 4) {
                result.add(addressStem(token));
            } else {
                result.add(token);
            }
        }
        return result;
    }

    private void requireActCreator(
            String text,
            GenerateClaimRequest.Party creditor,
            List<String> errors
    ) {
        if (creditor == null || !hasText(creditor.name())) return;
        String normalized = normalize(text);
        String party = normalize(creditor.name());
        boolean unilateral = normalized.contains("односторон");
        boolean passive = anyNear(normalized, party, "составлен", 240)
                && anyNear(normalized, "акт", "составлен", 180);
        boolean active = anyNear(normalized, party, "составил", 220)
                && anyNear(normalized, "составил", "акт", 180);
        if (!unilateral || (!passive && !active)) {
            errors.add("Act does not identify case_facts.creditor as the unilateral document creator");
        }
    }

    private boolean containsFutureActIntent(String text) {
        String normalized = normalize(text);
        return normalized.contains("намерении составить акт")
                || normalized.contains("намерение составить акт")
                || normalized.contains("будет составлен акт")
                || normalized.contains("предстоящем составлении акта")
                || normalized.contains("планирует составить акт");
    }

    private boolean anyNear(String text, String first, String second, int maxDistance) {
        if (!hasText(text) || !hasText(first) || !hasText(second)) return false;
        int firstIndex = text.indexOf(first);
        while (firstIndex >= 0) {
            int secondIndex = text.indexOf(second);
            while (secondIndex >= 0) {
                if (Math.abs(firstIndex - secondIndex) <= maxDistance) return true;
                secondIndex = text.indexOf(second, secondIndex + 1);
            }
            firstIndex = text.indexOf(first, firstIndex + 1);
        }
        return false;
    }


    private void requireAddress(String text, String expected, String field, List<String> errors) {
        if (!hasText(expected)) return;
        if (!containsAddress(text, expected)) {
            errors.add("document_text does not contain expected " + field + ": " + expected);
        }
    }

    private boolean containsAddress(String text, String expected) {
        if (normalize(text).contains(normalize(expected))) return true;

        List<String> expectedTerms = addressTerms(expected);
        List<String> actualTerms = addressTerms(text);
        if (expectedTerms.isEmpty() || actualTerms.isEmpty()) return false;

        Set<String> requiredWords = new HashSet<>();
        Set<String> requiredNumbers = new HashSet<>();
        for (String term : expectedTerms) {
            if (term.matches("\\d+")) requiredNumbers.add(term);
            else requiredWords.add(term);
        }
        if (requiredWords.isEmpty()) return false;

        for (int start = 0; start < actualTerms.size(); start++) {
            Set<String> words = new HashSet<>();
            int firstWordIndex = -1;
            int lastWordIndex = -1;
            int endExclusive = Math.min(actualTerms.size(), start + ADDRESS_TOKEN_WINDOW);
            for (int index = start; index < endExclusive; index++) {
                String term = actualTerms.get(index);
                if (requiredWords.contains(term)) {
                    words.add(term);
                    if (firstWordIndex < 0) firstWordIndex = index;
                    lastWordIndex = index;
                }
                if (!words.containsAll(requiredWords)) continue;
                if (requiredNumbers.isEmpty()) return true;

                int numberStart = Math.max(0, firstWordIndex - ADDRESS_NUMBER_MAX_DISTANCE);
                int numberEnd = Math.min(actualTerms.size(), lastWordIndex + ADDRESS_NUMBER_MAX_DISTANCE + 1);
                Set<String> nearbyNumbers = new HashSet<>();
                for (int numberIndex = numberStart; numberIndex < numberEnd; numberIndex++) {
                    String candidate = actualTerms.get(numberIndex);
                    if (candidate.matches("\\d+")) nearbyNumbers.add(candidate);
                }
                if (nearbyNumbers.containsAll(requiredNumbers)) return true;
            }
        }
        return false;
    }

    private List<String> addressTerms(String value) {
        if (!hasText(value)) return List.of();
        List<String> result = new ArrayList<>();
        String[] rawTokens = value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .split("[^\\p{L}\\p{N}]+");
        for (String rawToken : rawTokens) {
            if (rawToken.isBlank()) continue;
            String term = addressStem(rawToken);
            if (!term.isBlank() && !ADDRESS_STOP_WORDS.contains(term)) result.add(term);
        }
        return result;
    }

    private String addressStem(String token) {
        if (token.matches("\\d+")) return token;
        if (token.length() <= 3) return token;
        String[] endings = {
                "иями", "ями", "ами", "ого", "ему", "ому", "ыми", "ими",
                "ая", "яя", "ое", "ее", "ый", "ий", "ой", "ей",
                "ам", "ям", "ах", "ях", "ом", "ем", "ов", "ев",
                "а", "я", "у", "ю", "е", "ы", "и"
        };
        for (String ending : endings) {
            if (token.endsWith(ending) && token.length() - ending.length() >= 4) {
                return token.substring(0, token.length() - ending.length());
            }
        }
        return token;
    }

    private void requireTimeWindow(String text, String expected, String field, List<String> errors) {
        if (!hasText(expected)) return;
        if (!containsTimeWindow(text, expected)) {
            errors.add("document_text does not contain expected " + field + ": " + expected);
        }
    }

    private boolean containsTimeWindow(String text, String expected) {
        List<TimeMention> expectedTimes = extractTimes(expected);
        if (expectedTimes.size() < 2) return normalize(text).contains(normalize(expected));

        String expectedStart = expectedTimes.get(0).value();
        String expectedEnd = expectedTimes.get(1).value();
        List<TimeMention> actualTimes = extractTimes(text);
        for (int startIndex = 0; startIndex < actualTimes.size(); startIndex++) {
            TimeMention start = actualTimes.get(startIndex);
            if (!start.value().equals(expectedStart)) continue;
            for (int endIndex = startIndex + 1; endIndex < actualTimes.size(); endIndex++) {
                TimeMention end = actualTimes.get(endIndex);
                if (end.position() - start.position() > TIME_WINDOW_MAX_DISTANCE) break;
                if (end.value().equals(expectedEnd)) return true;
            }
        }
        return false;
    }

    private List<TimeMention> extractTimes(String value) {
        if (!hasText(value)) return List.of();
        List<TimeMention> result = new ArrayList<>();
        Matcher matcher = CLOCK_TIME.matcher(value);
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            result.add(new TimeMention(String.format(Locale.ROOT, "%02d:%s", hour, matcher.group(2)), matcher.start()));
        }
        return result;
    }

    private record TimeMention(String value, int position) {
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

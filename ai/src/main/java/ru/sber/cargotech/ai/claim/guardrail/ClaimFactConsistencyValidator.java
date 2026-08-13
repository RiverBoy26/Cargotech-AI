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
                    + "(?:\\s*(\\d{1,2})\\s*коп(?:еек|ейки|ейка|\\.)?)?"
    );
    private static final Pattern CLOCK_TIME_PATTERN = Pattern.compile(
            "(?<!\\d)([01]?\\d|2[0-3])[:.]([0-5]\\d)(?!\\d)"
    );
    private static final Pattern DANGLING_ACT_NUMBER_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])акт(?:ом|а|у|е|ы)?\\s*№\\s*(?:от(?![\\p{L}\\p{N}_])|[,.;:]|$)"
    );
    private static final Pattern BANK_DETAILS_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:бик|"
                    + "корреспондентск\\p{L}*\\s+сч[её]т\\p{L}*|"
                    + "расч[её]тн\\p{L}*\\s+сч[её]т\\p{L}*|"
                    + "р\\s*/\\s*с|к\\s*/\\s*с)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern BANK_DETAILS_MENTION_PATTERN = Pattern.compile(
            "(?iu)банковск\\p{L}*\\s+реквизит\\p{L}*"
    );
    private static final Pattern ATTACHMENTS_SECTION_PATTERN = Pattern.compile(
            "(?imu)^\\s*приложени[ея]\\s*:"
    );
    private static final Pattern ISO_DATE_IN_CLAIM_PATTERN = Pattern.compile(
            "(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)"
    );
    private static final Pattern TECHNICAL_ENUM_IN_CLAIM_PATTERN = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(?:UNPAID|PAID|PARTIALLY_PAID|UNKNOWN|RUB|CONTRACT_PENALTY|NONE)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern MACHINE_RUB_AMOUNT_PATTERN = Pattern.compile(
            "(?iu)(?<!\\d)\\d[\\d \\u00A0]*\\.\\d{2}\\s*(?:руб(?:лей|ля|ль|\\.)?|₽)"
    );
    private static final Pattern CONFIRMATION_SUBSTITUTION_PATTERN = Pattern.compile(
            "(?iu)(?:неподтверждени\\p{L}*|отсутстви\\p{L}*\\s+подтверждени\\p{L}*)\\s+(?:факт\\p{L}*\\s+)?(?:подач\\p{L}*|предоставлени\\p{L}*)"
                    + "|(?:подач\\p{L}*|предоставлени\\p{L}*)\\s+(?:транспортн\\p{L}*\\s+средств\\p{L}*\\s+)?не\\s+подтвержден\\p{L}*"
    );
    private static final Pattern POSITIVE_VEHICLE_PROVISION_PATTERN = Pattern.compile(
            "(?iu)(?<!не\\s)(?<!не\\sбыло\\s)(?:транспортн\\p{L}*\\s+средств\\p{L}*|тс)\\s+(?:было\\s+)?(?:предоставлен\\p{L}*|подан\\p{L}*|прибыл\\p{L}*)(?!\\s+не\\s+был\\p{L}*)"
    );
    private static final Pattern UNSUPPORTED_VEHICLE_IDENTITY_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:марк(?:а|и)|модел\\p{L}*|госномер\\p{L}*|государственн\\p{L}*\\s+регистрационн\\p{L}*\\s+номер\\p{L}*|регистрационн\\p{L}*\\s+номер\\p{L}*|водител\\p{L}*)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern UNSUPPORTED_INCIDENT_CIRCUMSTANCE_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}_])(?:поломк\\p{L}*|неисправност\\p{L}*|дтп|пробк\\p{L}*|опоздал\\p{L}*|покинул\\p{L}*|не\\s+дождал\\p{L}*|отказал\\p{L}*)(?![\\p{L}\\p{N}_])"
    );
    private static final Pattern EXPLICIT_NON_PROVISION_PATTERN = Pattern.compile(
            "(?iu)(?:непредоставлени\\p{L}*\\s+(?:транспортн\\p{L}*\\s+средств\\p{L}*|тс)"
                    + "|срыв\\p{L}*\\s+погрузк\\p{L}*"
                    + "|(?:транспортн\\p{L}*\\s+средств\\p{L}*|тс)\\s+"
                    + "(?:не\\s+(?:был\\p{L}*\\s+)?(?:предоставлен\\p{L}*|подан\\p{L}*|прибыл\\p{L}*)"
                    + "|(?:предоставлен\\p{L}*|подан\\p{L}*)\\s+не\\s+был\\p{L}*))"
    );
    private static final Set<String> ADDRESS_STOP_WORDS = Set.of(
            "г", "город", "по", "адрес", "адресу", "погрузк", "мест",
            "на", "в", "во", "у", "д", "дом", "корп", "корпус",
            "стр", "строен"
    );
    private static final int ADDRESS_TOKEN_WINDOW = 20;
    private static final int TIME_WINDOW_MAX_DISTANCE = 120;

    private static final List<DateTimeFormatter> INPUT_DATE_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd.MM.uuuu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu")
    );
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
        String summary = response.summaryForLawyer() == null ? "" : response.summaryForLawyer();
        String narrative = text + "\n" + summary;
        GenerateClaimRequest.CaseFacts facts = request.caseFacts();

        validateClaimIdentity(facts, text, errors);
        validateSignatory(facts.signatory(), text, errors);
        validateExcludedSections(facts, text, errors);
        validatePresentationQuality(text, errors);
        validateParty("creditor", facts.creditor(), text, errors);
        validateParty("debtor", facts.debtor(), text, errors);
        validateUnknownInns(facts, narrative, errors);
        validateContract(facts.contract(), text, errors);
        validateAmounts(request.backendCalculation(), narrative, errors);

        if (facts.claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY) {
            validatePaymentDelayFacts(facts, text, errors);
            validatePaymentDelaySemantics(facts, request.backendCalculation(), text, errors);
        } else if (facts.claimType() == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            validateLoadingFailureFacts(facts, request.backendCalculation(), text, narrative, errors, warnings);
        }

        validateAttachments(request, response, errors, warnings);

        for (String modelWarning : safeList(response.warnings())) {
            if (modelWarning != null && !modelWarning.isBlank()) {
                warnings.add("Model warning: " + modelWarning.trim());
            }
        }
    }

    private void validateClaimIdentity(
            GenerateClaimRequest.CaseFacts facts,
            String text,
            List<String> errors
    ) {
        if (!hasText(facts.claimNumber())) {
            return;
        }
        requireTextValue(text, facts.claimNumber(), "claim_number", errors);
        requireDate(text, facts.claimDate(), "claim_date", errors);
    }

    private void validateSignatory(
            GenerateClaimRequest.SignatoryFacts signatory,
            String text,
            List<String> errors
    ) {
        if (signatory == null) {
            return;
        }
        requireTextValue(text, signatory.position(), "signatory.position", errors);
        requireTextValue(text, signatory.name(), "signatory.name", errors);
        requireTextValue(text, signatory.authority(), "signatory.authority", errors);
    }

    private void validateExcludedSections(
            GenerateClaimRequest.CaseFacts facts,
            String text,
            List<String> errors
    ) {
        if (BANK_DETAILS_PATTERN.matcher(text).find() || BANK_DETAILS_MENTION_PATTERN.matcher(text).find()) {
            errors.add("claim_text must not contain or mention bank details in current scope");
        }
        if (ATTACHMENTS_SECTION_PATTERN.matcher(text).find()) {
            errors.add("claim_text must not contain an attachments section in current scope");
        }
    }

    private void validatePresentationQuality(String text, List<String> errors) {
        Matcher isoDateMatcher = ISO_DATE_IN_CLAIM_PATTERN.matcher(text);
        if (isoDateMatcher.find()) {
            errors.add("claim_text contains machine ISO date: " + isoDateMatcher.group());
        }

        Matcher enumMatcher = TECHNICAL_ENUM_IN_CLAIM_PATTERN.matcher(text);
        if (enumMatcher.find()) {
            errors.add("claim_text contains technical enum/code: " + enumMatcher.group());
        }

        Matcher machineAmountMatcher = MACHINE_RUB_AMOUNT_PATTERN.matcher(text);
        if (machineAmountMatcher.find()) {
            errors.add("claim_text contains machine-formatted RUB amount: " + machineAmountMatcher.group());
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
        requireTextValue(text, party.bankDetails(), role + ".bank_details", errors);
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
        requireClaimResponseDeadline(text, contract.claimResponseDays(), errors);
    }

    private void requireClaimResponseDeadline(String text, Integer days, List<String> errors) {
        if (days == null || days <= 0) {
            return;
        }

        Pattern daysPattern = Pattern.compile(
                "(?iu)(?<!\\d)" + Pattern.quote(String.valueOf(days))
                        + "\\s+календарн\\p{L}*\\s+дн\\p{L}*(?!\\d)"
        );
        String normalized = normalize(text);
        boolean hasDays = daysPattern.matcher(text).find();
        boolean tiedToReceipt = normalized.contains("получени") && normalized.contains("претензи");

        if (!hasDays || !tiedToReceipt) {
            errors.add("claim_text does not contain expected contract.claim_response_days: "
                    + days + " calendar days from receipt of the claim");
        }
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
        if (!hasText(shipment.actNumber())
                && hasText(shipment.actDate())
                && DANGLING_ACT_NUMBER_PATTERN.matcher(text).find()) {
            errors.add("claim_text contains a dangling act number marker while shipment.act_number is empty");
        }
        requireTextValue(text, shipment.route(), "shipment.route", errors);
    }

    private void validatePaymentDelaySemantics(
            GenerateClaimRequest.CaseFacts facts,
            GenerateClaimRequest.BackendCalculation calculation,
            String text,
            List<String> errors
    ) {
        if (facts == null || text == null) {
            return;
        }

        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();

        // There is no order/application date in GenerateClaimRequest.
        // A model must not turn order_number into "заказ № ... от <invented date>".
        if (shipment != null && hasText(shipment.orderNumber())) {
            String orderDatePattern = "(?isu)(?:заказ|заявк)\\p{L}*[^.!?\\n]{0,100}"
                    + "(?:№\\s*)?" + Pattern.quote(shipment.orderNumber())
                    + "[^.!?\\n]{0,60}\\sот\\s+\\d{1,2}\\s+"
                    + "(?:января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)"
                    + "\\s+\\d{4}";
            if (Pattern.compile(orderDatePattern).matcher(text).find()) {
                errors.add("claim_text invents an order/application date absent from case_facts.shipment");
            }
        }

        // Accountant confirmation in this workflow confirms the payment status
        // (non-payment/partial payment), not issuance/receipt of documents or the
        // legal moment when the payment term started.
        if (facts.payment() != null && Boolean.TRUE.equals(facts.payment().paymentConfirmedByAccountant())) {
            Pattern accountantOverclaim = Pattern.compile(
                    "(?isu)бухгалтер\\p{L}*[^.!?\\n]{0,220}"
                            + "(?:выставлен\\p{L}*\\s+документ\\p{L}*"
                            + "|получен\\p{L}*\\s+(?:полн\\p{L}*\\s+)?комплект\\p{L}*\\s+документ\\p{L}*"
                            + "|наступлен\\p{L}*\\s+срок\\p{L}*\\s+(?:платеж\\p{L}*|оплат\\p{L}*))"
            );
            if (accountantOverclaim.matcher(text).find()) {
                errors.add("claim_text overstates accountant confirmation beyond the confirmed payment status");
            }
        }

        // ARTICLE_395/LEGAL_INTEREST is interest for use of another's money,
        // not contractual penalty / fine / late fee.
        if (calculation != null
                && calculation.penaltyType() == GenerateClaimRequest.PenaltyType.LEGAL_INTEREST
                && calculation.penaltyAmount() != null
                && calculation.penaltyAmount().compareTo(BigDecimal.ZERO) > 0) {
            Pattern wrongInterestTerm = Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}_])(?:неустойк\\p{L}*|штраф\\p{L}*|пен(?:я|и|ей|ю))(?![\\p{L}\\p{N}_])"
            );
            if (wrongInterestTerm.matcher(text).find()) {
                errors.add("claim_text describes LEGAL_INTEREST as contractual penalty/fine/late fee");
            }
        }

        // claim_response_days is the deadline for a written response to the
        // claim. It must not silently become a new payment deadline.
        Integer responseDays = facts.contract() == null ? null : facts.contract().claimResponseDays();
        if (responseDays != null && responseDays > 0) {
            Pattern paymentDeadline = Pattern.compile(
                    "(?iu)(?:оплат\\p{L}*|перечисл\\p{L}*|погас\\p{L}*)"
                            + "[^.!?\\n]{0,180}"
                            + "(?:в\\s+течение\\s+)?"
                            + Pattern.quote(String.valueOf(responseDays))
                            + "\\s+календарн\\p{L}*\\s+дн\\p{L}*"
            );
            if (paymentDeadline.matcher(text).find()) {
                errors.add("claim_text incorrectly uses contract.claim_response_days as a payment deadline");
            }
        }
    }

    private void validateLoadingFailureFacts(
            GenerateClaimRequest.CaseFacts facts,
            GenerateClaimRequest.BackendCalculation calculation,
            String text,
            String narrative,
            List<String> errors,
            List<String> warnings
    ) {
        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();
        if (shipment == null) {
            return;
        }

        requireTextValue(text, shipment.orderNumber(), "shipment.order_number", errors);
        requireDate(text, shipment.loadingDate(), "shipment.loading_date", errors);
        requireAddress(text, shipment.loadingAddress(), "shipment.loading_address", errors);
        requireTimeWindow(text, shipment.loadingTimeWindow(), "shipment.loading_time_window", errors);
        requireTextValue(text, shipment.route(), "shipment.route", errors);
        requireTextValue(text, shipment.actNumber(), "shipment.act_number", errors);
        requireDate(text, shipment.actDate(), "shipment.act_date", errors);
        requireVehicleRequirements(text, shipment.vehicleRequirements(), errors);
        validateLoadingFailureSemantics(narrative, shipment, calculation, errors, warnings);
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
        addAmount(allowed, calculation.originalObligationAmount());
        addAmount(allowed, calculation.paidAmount());
        addAmount(allowed, calculation.penaltyAmount());
        addAmount(allowed, calculation.totalAmount());

        if (calculation.originalObligationAmount() != null
                && calculation.originalObligationAmount().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.originalObligationAmount(), "original_obligation_amount", errors);
        }
        if (calculation.paidAmount() != null && calculation.paidAmount().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.paidAmount(), "paid_amount", errors);
        }
        if (calculation.principalDebt() != null && calculation.principalDebt().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.principalDebt(), "principal_debt", errors);
        }
        if (calculation.penaltyAmount() != null && calculation.penaltyAmount().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.penaltyAmount(), "penalty_amount", errors);
        }
        if (calculation.totalAmount() != null && calculation.totalAmount().compareTo(BigDecimal.ZERO) > 0) {
            requireAmount(text, calculation.totalAmount(), "total_amount", errors);
        }
        requireDate(text, calculation.overdueStartDate(), "overdue_start_date", errors);
        requireDate(text, calculation.overdueEndDate(), "overdue_end_date", errors);

        Matcher matcher = RUB_AMOUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            BigDecimal found = parseRubAmount(matcher);
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

        if (facts.claimType() == GenerateClaimRequest.ClaimType.PAYMENT_DELAY
                && response.attachments() != null
                && !response.attachments().isEmpty()) {
            errors.add("PAYMENT_DELAY attachments must be empty in current scope");
            return;
        }

        if (facts.contract() != null && hasText(facts.contract().documentId())) {
            allowed.add(GenerateClaimResponse.DocumentType.CONTRACT);
        }
        if (request.backendCalculation() != null) allowed.add(GenerateClaimResponse.DocumentType.CALCULATION);
        if (shipment != null) {
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
                continue;
            }

            validateAttachmentIdentity(attachment, facts, errors);
        }

        validateRequiredLoadingFailureAct(facts, response.attachments(), errors);

        if ((response.attachments() == null || response.attachments().isEmpty())
                && facts.claimType() == GenerateClaimRequest.ClaimType.LOADING_FAILURE) {
            warnings.add("Model returned no attachments");
        }
    }

    private void validateRequiredLoadingFailureAct(
            GenerateClaimRequest.CaseFacts facts,
            List<GenerateClaimResponse.Attachment> attachments,
            List<String> errors
    ) {
        if (facts == null
                || facts.claimType() != GenerateClaimRequest.ClaimType.LOADING_FAILURE
                || facts.shipment() == null
                || !Boolean.TRUE.equals(facts.shipment().failureConfirmedByDispatcher())) {
            return;
        }

        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();
        if (!hasText(shipment.actNumber()) && !hasText(shipment.actDate())) {
            return;
        }

        boolean matchingRequiredAct = false;
        for (GenerateClaimResponse.Attachment attachment : safeList(attachments)) {
            if (attachment == null
                    || attachment.documentType() != GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
                    || !Boolean.TRUE.equals(attachment.required())
                    || !hasText(attachment.documentName())) {
                continue;
            }
            boolean numberMatches = !hasText(shipment.actNumber())
                    || normalize(attachment.documentName()).contains(normalize(shipment.actNumber()));
            boolean dateMatches = !hasText(shipment.actDate())
                    || containsDate(attachment.documentName(), shipment.actDate());
            if (numberMatches && dateMatches) {
                matchingRequiredAct = true;
                break;
            }
        }

        if (!matchingRequiredAct) {
            errors.add("LOADING_FAILURE claim must include a required LOADING_FAILURE_ACT attachment matching shipment.act_number and shipment.act_date");
        }
    }

    private void validateAttachmentIdentity(
            GenerateClaimResponse.Attachment attachment,
            GenerateClaimRequest.CaseFacts facts,
            List<String> errors
    ) {
        if (!hasText(attachment.documentName())) {
            errors.add("Attachment " + attachment.documentType() + " has empty document_name");
            return;
        }

        GenerateClaimRequest.ShipmentFacts shipment = facts.shipment();

        switch (attachment.documentType()) {
            case CONTRACT -> requireAttachmentValue(
                    attachment.documentName(),
                    facts.contract() == null ? null : facts.contract().contractNumber(),
                    "contract.contract_number",
                    errors
            );
            case ACT -> requireAttachmentValue(
                    attachment.documentName(),
                    shipment == null ? null : shipment.actNumber(),
                    "shipment.act_number",
                    errors
            );
            case TTN -> requireAttachmentValue(
                    attachment.documentName(),
                    shipment == null ? null : shipment.ttnNumber(),
                    "shipment.ttn_number",
                    errors
            );
            case INVOICE -> requireAttachmentValue(
                    attachment.documentName(),
                    shipment == null ? null : shipment.invoiceNumber(),
                    "shipment.invoice_number",
                    errors
            );
            case TRANSPORT_ORDER -> requireAttachmentValue(
                    attachment.documentName(),
                    shipment == null ? null : shipment.orderNumber(),
                    "shipment.order_number",
                    errors
            );
            case LOADING_FAILURE_ACT -> {
                requireAttachmentValue(
                        attachment.documentName(),
                        shipment == null ? null : shipment.actNumber(),
                        "shipment.act_number",
                        errors
                );
                if (shipment != null
                        && hasText(shipment.actDate())
                        && !containsDate(attachment.documentName(), shipment.actDate())) {
                    errors.add("Attachment does not contain expected shipment.act_date: " + shipment.actDate());
                }
            }
            default -> {
                // Для расчёта, выписки и иных разрешённых типов
                // отдельного идентификатора во входном DTO пока нет.
            }
        }
    }

    private void requireAttachmentValue(
            String documentName,
            String expected,
            String field,
            List<String> errors
    ) {
        if (!hasText(expected)) {
            return;
        }
        if (!normalize(documentName).contains(normalize(expected))) {
            errors.add("Attachment does not contain expected " + field + ": " + expected);
        }
    }

    private void validateLoadingFailureSemantics(
            String text,
            GenerateClaimRequest.ShipmentFacts shipment,
            GenerateClaimRequest.BackendCalculation calculation,
            List<String> errors,
            List<String> warnings
    ) {
        String normalized = normalize(text);

        if (CONFIRMATION_SUBSTITUTION_PATTERN.matcher(text).find()) {
            errors.add("claim_text replaces confirmed vehicle non-provision with absence of confirmation");
        }
        if (!containsExplicitNonProvision(text)) {
            errors.add("claim_text does not clearly state confirmed vehicle non-provision");
        }
        if (POSITIVE_VEHICLE_PROVISION_PATTERN.matcher(text).find()) {
            errors.add("claim_text contradicts case_facts by asserting that the vehicle was provided or arrived");
        }

        String allowedVehicleFacts = normalize(shipment == null ? null : shipment.vehicleRequirements());
        Matcher identityMatcher = UNSUPPORTED_VEHICLE_IDENTITY_PATTERN.matcher(text);
        while (identityMatcher.find()) {
            String found = normalize(identityMatcher.group());
            if (!allowedVehicleFacts.contains(found)) {
                errors.add("claim_text invents unsupported vehicle identity detail: " + identityMatcher.group());
                break;
            }
        }
        if (UNSUPPORTED_INCIDENT_CIRCUMSTANCE_PATTERN.matcher(text).find()) {
            errors.add("claim_text invents a cause or incident circumstance absent from case_facts");
        }
        if (calculation != null
                && calculation.penaltyType() == GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY
                && (normalized.contains("компенсаци")
                || normalized.contains("возмещение ущерба")
                || normalized.contains("возместить ущерб")
                || normalized.contains("убытк"))) {
            errors.add("claim_text changes CONTRACT_PENALTY into compensation, damages or loss recovery");
        }
        if (normalized.contains("непредставлен")) {
            warnings.add("Use the legal term «непредоставление транспортного средства», not «непредставление»");
        }
    }

    private boolean containsExplicitNonProvision(String text) {
        return hasText(text) && EXPLICIT_NON_PROVISION_PATTERN.matcher(text).find();
    }

    private void requireVehicleRequirements(String text, String expected, List<String> errors) {
        if (!hasText(expected)) return;
        Set<String> required = new LinkedHashSet<>(semanticTerms(expected));
        Set<String> actual = new HashSet<>(semanticTerms(text));
        if (!actual.containsAll(required)) {
            errors.add("claim_text does not contain expected shipment.vehicle_requirements: " + expected);
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

    private void requireAddress(String text, String expected, String field, List<String> errors) {
        if (!hasText(expected)) {
            return;
        }
        if (!containsAddress(text, expected)) {
            errors.add("claim_text does not contain expected " + field + ": " + expected);
        }
    }

    private boolean containsAddress(String text, String expected) {
        if (normalize(text).contains(normalize(expected))) {
            return true;
        }

        List<String> expectedTerms = addressTerms(expected);
        List<String> actualTerms = addressTerms(text);
        if (expectedTerms.isEmpty() || actualTerms.isEmpty()) {
            return false;
        }

        Set<String> required = new HashSet<>(expectedTerms);
        for (int start = 0; start < actualTerms.size(); start++) {
            Set<String> window = new HashSet<>();
            int endExclusive = Math.min(actualTerms.size(), start + ADDRESS_TOKEN_WINDOW);
            for (int index = start; index < endExclusive; index++) {
                window.add(actualTerms.get(index));
                if (window.containsAll(required)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> addressTerms(String value) {
        if (!hasText(value)) {
            return List.of();
        }

        List<String> result = new ArrayList<>();
        String[] rawTokens = value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .split("[^\\p{L}\\p{N}]+");

        for (String rawToken : rawTokens) {
            if (rawToken.isBlank()) {
                continue;
            }
            String term = addressStem(rawToken);
            if (!term.isBlank() && !ADDRESS_STOP_WORDS.contains(term)) {
                result.add(term);
            }
        }
        return result;
    }

    private String addressStem(String token) {
        if (token.matches("\\d+")) {
            return token;
        }
        if (token.length() <= 3) {
            return token;
        }

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
        if (!hasText(expected)) {
            return;
        }
        if (!containsTimeWindow(text, expected)) {
            errors.add("claim_text does not contain expected " + field + ": " + expected);
        }
    }

    private boolean containsTimeWindow(String text, String expected) {
        List<TimeMention> expectedTimes = extractTimes(expected);
        if (expectedTimes.size() < 2) {
            return normalize(text).contains(normalize(expected));
        }

        String expectedStart = expectedTimes.get(0).value();
        String expectedEnd = expectedTimes.get(1).value();
        List<TimeMention> actualTimes = extractTimes(text);

        for (int startIndex = 0; startIndex < actualTimes.size(); startIndex++) {
            TimeMention start = actualTimes.get(startIndex);
            if (!start.value().equals(expectedStart)) {
                continue;
            }
            for (int endIndex = startIndex + 1; endIndex < actualTimes.size(); endIndex++) {
                TimeMention end = actualTimes.get(endIndex);
                if (end.position() - start.position() > TIME_WINDOW_MAX_DISTANCE) {
                    break;
                }
                if (end.value().equals(expectedEnd)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<TimeMention> extractTimes(String value) {
        if (!hasText(value)) {
            return List.of();
        }

        List<TimeMention> result = new ArrayList<>();
        Matcher matcher = CLOCK_TIME_PATTERN.matcher(value);
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            String normalizedTime = String.format(Locale.ROOT, "%02d:%s", hour, matcher.group(2));
            result.add(new TimeMention(normalizedTime, matcher.start()));
        }
        return result;
    }

    private record TimeMention(String value, int position) {
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
        String normalizedText = normalize(text);
        if (normalizedText.contains(normalize(expected))) {
            return true;
        }

        LocalDate date = parseDate(expected);
        if (date == null) {
            return false;
        }

        List<String> acceptedRepresentations = List.of(
                date.toString(),
                date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu")),
                date.format(DateTimeFormatter.ofPattern("dd-MM-uuuu")),
                date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear(),
                date.getDayOfMonth() + " " + MONTHS[date.getMonthValue() - 1] + " " + date.getYear() + " года"
        );

        return acceptedRepresentations.stream()
                .map(this::normalize)
                .anyMatch(normalizedText::contains);
    }

    private LocalDate parseDate(String value) {
        for (DateTimeFormatter formatter : INPUT_DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported input format.
            }
        }
        return null;
    }

    private void requireAmount(String text, BigDecimal amount, String field, List<String> errors) {
        Matcher matcher = RUB_AMOUNT_PATTERN.matcher(text);
        while (matcher.find()) {
            BigDecimal found = parseRubAmount(matcher);
            if (found != null && sameAmount(found, amount)) {
                return;
            }
        }
        errors.add("claim_text does not contain expected " + field + ": " + amount.toPlainString() + " RUB");
    }

    private BigDecimal parseRubAmount(Matcher matcher) {
        if (matcher == null) {
            return null;
        }
        try {
            BigDecimal rubles = new BigDecimal(
                    matcher.group(1).replace(" ", "").replace("\u00A0", "").replace(',', '.')
            );
            String kopecksRaw = matcher.groupCount() >= 2 ? matcher.group(2) : null;
            if (kopecksRaw == null || kopecksRaw.isBlank() || matcher.group(1).contains(".") || matcher.group(1).contains(",")) {
                return rubles;
            }
            return rubles.add(new BigDecimal(kopecksRaw).movePointLeft(2));
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

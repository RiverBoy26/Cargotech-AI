package ru.sber.cargotech.claim.mapper;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimContractClause;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.enums.TermDayType;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Component
public class ClaimAiRequestMapper {

    public AiGenerateClaimRequest map(
            ClaimEntity claim,
            ClaimParty creditor,
            ClaimParty debtor,
            ClaimContract contract,
            ClaimShipment shipment,
            ClaimCalculation calculation,
            CurrentClaimUser currentUser
    ) {
        return map(claim, creditor, debtor, contract, shipment, calculation, currentUser, List.of());
    }

    public AiGenerateClaimRequest map(
            ClaimEntity claim,
            ClaimParty creditor,
            ClaimParty debtor,
            ClaimContract contract,
            ClaimShipment shipment,
            ClaimCalculation calculation,
            CurrentClaimUser currentUser,
            List<ClaimContractClause> clauses
    ) {
        AiGenerateClaimRequest.ClaimType claimType = mapClaimType(claim);

        return new AiGenerateClaimRequest(
                new AiGenerateClaimRequest.CaseFacts(
                        claim.getId().toString(),
                        claim.getClaimNumber(),
                        claimType,
                        mapParty(creditor, claim.getBankDetails()),
                        mapParty(debtor, null),
                        new AiGenerateClaimRequest.ContractFacts(
                                contract.getNumber(),
                                asString(contract.getSignedAt()),
                                claim.getResponseDeadlineDays() == null
                                        ? contract.getClaimResponseDays()
                                        : claim.getResponseDeadlineDays(),
                                mapTermDayType(contract.getClaimResponseDayType()),
                                contract.getDocumentId() == null ? null : contract.getDocumentId().toString()
                        ),
                        new AiGenerateClaimRequest.ShipmentFacts(
                                shipment.getOrderNumber(),
                                buildRoute(shipment),
                                null,
                                asString(shipment.getActSignedAt()),
                                null,
                                null,
                                asString(shipment.getLoadingDate()),
                                shipment.getRouteFrom(),
                                null,
                                null,
                                debtor.getName(),
                                null
                        ),
                        new AiGenerateClaimRequest.PaymentFacts(
                                asString(resolvePaymentDueDate(calculation)),
                                resolvePaymentStatus(calculation),
                                claim.isNonPaymentConfirmed()
                        ),
                        asString(resolveClaimDate(calculation)),
                        mapSignatory(claim, currentUser)
                ),
                new AiGenerateClaimRequest.BackendCalculation(
                        calculation.getRemainingDebt(),
                        mapPenaltyType(calculation),
                        penaltyRateText(calculation),
                        calculation.getOverdueDays(),
                        calculation.getPenaltyAmount(),
                        calculation.getTotalAmount(),
                        shipment.getCurrency(),
                        calculation.getFormula(),
                        asString(calculation.getOverdueStartDate()),
                        asString(resolveOverdueEndDate(calculation)),
                        calculation.getPrincipalDebt(),
                        calculation.getPaidAmount()
                ),
                buildContractContext(contract, clauses),
                defaultLegalContext(calculation),
                new AiGenerateClaimRequest.TemplateContext(
                        "claim-default-v2",
                        "Шаблон претензии CargoTech",
                        claimType,
                        List.of(
                                "Исходящий номер и дата претензии",
                                "Реквизиты сторон",
                                "Обстоятельства нарушения",
                                "Расчёт задолженности и неустойки",
                                "Правовое обоснование",
                                "Требования кредитора и срок ответа",
                                "Перечень приложений",
                                "Подпись представителя кредитора с основанием полномочий"
                        )
                ),
                List.of(),
                new AiGenerateClaimRequest.RagOptions(
                        contractRagReady(contract),
                        contract.getId().toString(),
                        contract.getClientId().toString(),
                        contract.getOrganizationId().toString()
                )
        );
    }

    private boolean contractRagReady(ClaimContract contract) {
        return contract.getStatus() == ru.sber.cargotech.claim.enums.ContractStatus.ACTIVE
            && contract.getExtractionStatus() == ru.sber.cargotech.claim.enums.ContractExtractionStatus.CONFIRMED
            && contract.getRagIndexStatus() == ru.sber.cargotech.claim.enums.ContractRagStatus.INDEXED
            && contract.getDocumentId() != null
            && contract.getDocumentId().equals(contract.getRagSourceDocumentId());
    }

    private AiGenerateClaimRequest.ClaimType mapClaimType(ClaimEntity claim) {
        return switch (claim.getClaimType()) {
            case PAYMENT_DELAY -> AiGenerateClaimRequest.ClaimType.PAYMENT_DELAY;
        };
    }

    private AiGenerateClaimRequest.Party mapParty(ClaimParty party, String bankDetails) {
        return new AiGenerateClaimRequest.Party(
                party.getName(),
                party.getInn(),
                party.getLegalAddress(),
                bankDetails
        );
    }

    private AiGenerateClaimRequest.SignatoryFacts mapSignatory(ClaimEntity claim, CurrentClaimUser currentUser) {
        String name = isBlank(claim.getSignerFullName())
                ? (currentUser == null ? null : currentUser.fullName())
                : claim.getSignerFullName();
        if (isBlank(name)) {
            return null;
        }
        String position = isBlank(claim.getSignerPosition())
                ? (currentUser != null && currentUser.hasRole("LAWYER")
                        ? "Юрист"
                        : "Представитель кредитора")
                : claim.getSignerPosition();
        return new AiGenerateClaimRequest.SignatoryFacts(name, position, claim.getSignerAuthority());
    }


    private String penaltyRateText(ClaimCalculation calculation) {
        if (calculation.getPenaltyType() == ru.sber.cargotech.claim.enums.PenaltyType.ARTICLE_395) {
            return "ключевая ставка Банка России по периодам";
        }
        return calculation.getPenaltyRate() == null
                ? null
                : calculation.getPenaltyRate().stripTrailingZeros().toPlainString();
    }

    private AiGenerateClaimRequest.PenaltyType mapPenaltyType(ClaimCalculation calculation) {
        return switch (calculation.getPenaltyType()) {
            case CONTRACT_PENALTY -> AiGenerateClaimRequest.PenaltyType.CONTRACT_PENALTY;
            case ARTICLE_395 -> AiGenerateClaimRequest.PenaltyType.LEGAL_INTEREST;
            case NONE -> AiGenerateClaimRequest.PenaltyType.NONE;
        };
    }

    private LocalDate resolvePaymentDueDate(ClaimCalculation calculation) {
        LocalDate overdueStartDate = calculation.getOverdueStartDate();
        return overdueStartDate == null ? null : overdueStartDate.minusDays(1);
    }

    private LocalDate resolveClaimDate(ClaimCalculation calculation) {
        return calculation != null && calculation.getCalculationDate() != null
                ? calculation.getCalculationDate()
                : LocalDate.now();
    }

    private LocalDate resolveOverdueEndDate(ClaimCalculation calculation) {
        if (calculation == null
                || calculation.getCalculationDate() == null
                || calculation.getOverdueDays() == null
                || calculation.getOverdueDays() <= 0) {
            return null;
        }
        // ClaimCalculationService counts overdue days as the half-open interval
        // [overdue_start_date, calculation_date), so the last accrued day is
        // always the calendar day immediately before calculation_date.
        return calculation.getCalculationDate().minusDays(1);
    }

    private AiGenerateClaimRequest.PaymentStatus resolvePaymentStatus(ClaimCalculation calculation) {
        if (calculation.getRemainingDebt() == null) {
            return AiGenerateClaimRequest.PaymentStatus.UNKNOWN;
        }
        if (calculation.getRemainingDebt().signum() == 0) {
            return AiGenerateClaimRequest.PaymentStatus.PAID;
        }
        if (calculation.getPaidAmount() != null && calculation.getPaidAmount().signum() > 0) {
            return AiGenerateClaimRequest.PaymentStatus.PARTIALLY_PAID;
        }
        return AiGenerateClaimRequest.PaymentStatus.UNPAID;
    }

    private List<AiGenerateClaimRequest.ContractContextChunk> buildContractContext(
            ClaimContract contract,
            List<ClaimContractClause> clauses
    ) {
        List<AiGenerateClaimRequest.ContractContextChunk> context = new ArrayList<>();
        Set<ru.sber.cargotech.claim.enums.ClauseType> exactTypes = EnumSet.noneOf(
                ru.sber.cargotech.claim.enums.ClauseType.class
        );
        if (clauses != null) {
            clauses.stream()
                    .filter(ClaimContractClause::isActive)
                    .filter(clause -> !isBlank(clause.getText()))
                    .forEach(clause -> {
                        exactTypes.add(clause.getClauseType());
                        String section = isBlank(clause.getSectionName())
                                ? "Пункт договора"
                                : clause.getSectionName();
                        if (clause.getSourcePage() != null) {
                            section += " (страница " + clause.getSourcePage() + ")";
                        }
                        context.add(new AiGenerateClaimRequest.ContractContextChunk(
                                "contract-clause-" + clause.getId(),
                                clause.getClauseNumber(),
                                section,
                                clause.getClauseType() == null ? null : clause.getClauseType().name(),
                                clause.getText()
                        ));
                    });
        }
        String prefix = "contract-card-" + contract.getId();

        if (!exactTypes.contains(ru.sber.cargotech.claim.enums.ClauseType.PAYMENT_TERMS)
                && contract.getPaymentDays() != null && contract.getPaymentDays() >= 0) {
            context.add(new AiGenerateClaimRequest.ContractContextChunk(
                    prefix + "-payment-term",
                    null,
                    "Структурированные условия оплаты",
                    ru.sber.cargotech.claim.enums.ClauseType.PAYMENT_TERMS.name(),
                    "Оплата должна быть произведена в течение " + contract.getPaymentDays()
                            + " " + termDayTypeLabel(contract.getPaymentDayType()) + ". Начало отсчёта срока: "
                            + paymentStartEventLabel(contract.getPaymentStartEvent())
                            + paymentScheduleLabel(contract)
                            + ". Номер пункта договора в карточке не указан; в тексте следует писать «согласно условиям договора»."
            ));
        }

        if (!exactTypes.contains(ru.sber.cargotech.claim.enums.ClauseType.PENALTY)
                && contract.getPenaltyType() != null) {
            String rate = contract.getPenaltyRate() == null
                    ? "ставка в карточке не указана"
                    : "ставка " + contract.getPenaltyRate().stripTrailingZeros().toPlainString() + "%";
            String cap = penaltyCapLabel(contract);
            context.add(new AiGenerateClaimRequest.ContractContextChunk(
                    prefix + "-penalty",
                    null,
                    "Структурированные условия ответственности",
                    ru.sber.cargotech.claim.enums.ClauseType.PENALTY.name(),
                    "Вид ответственности за просрочку: " + penaltyTypeLabel(contract.getPenaltyType())
                            + "; " + rate + cap
                            + ". Номер пункта договора в карточке не указан; запрещено выдумывать номер пункта."
            ));
        }

        if (!exactTypes.contains(ru.sber.cargotech.claim.enums.ClauseType.CLAIM_PROCEDURE)
                && contract.getClaimResponseDays() != null && contract.getClaimResponseDays() > 0) {
            context.add(new AiGenerateClaimRequest.ContractContextChunk(
                    prefix + "-pretrial-response",
                    null,
                    "Структурированный срок ответа на претензию",
                    ru.sber.cargotech.claim.enums.ClauseType.CLAIM_PROCEDURE.name(),
                    "Срок направления ответа на претензию: " + contract.getClaimResponseDays()
                            + " " + termDayTypeLabel(contract.getClaimResponseDayType())
                            + " с даты получения претензии. Номер пункта договора в карточке не указан."
            ));
        }

        return List.copyOf(context);
    }

    private String paymentScheduleLabel(ClaimContract contract) {
        if (contract.getPaymentScheduleType() == null || contract.getPaymentWeekDays() == null
                || contract.getPaymentWeekDays().isBlank()) {
            return "";
        }
        String days = java.util.Arrays.stream(contract.getPaymentWeekDays().split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(this::weekDayLabel)
            .collect(java.util.stream.Collectors.joining(", "));
        if (days.isBlank()) return "";
        return switch (contract.getPaymentScheduleType()) {
            case NEXT_PAYMENT_DAY ->
                ". Если расчётная дата не является платёжным днём, срок переносится на ближайший следующий платёжный день (" + days + ")";
            case NEXT_PAYMENT_DAY_AFTER_TERM ->
                ". Оплата производится в первый платёжный день строго после истечения расчётного срока (" + days + ")";
        };
    }

    private String weekDayLabel(String value) {
        try {
            return switch (DayOfWeek.valueOf(value)) {
                case MONDAY -> "понедельник";
                case TUESDAY -> "вторник";
                case WEDNESDAY -> "среда";
                case THURSDAY -> "четверг";
                case FRIDAY -> "пятница";
                case SATURDAY -> "суббота";
                case SUNDAY -> "воскресенье";
            };
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private String paymentStartEventLabel(PaymentStartEvent event) {
        if (event == null) {
            return "не указано";
        }
        return switch (event) {
            case ACT_SIGNED -> "дата подписания акта";
            case UNLOADING_DATE -> "дата выгрузки";
            case TTN_SIGNED -> "дата подписания транспортной накладной";
            case INVOICE_DATE -> "дата выставления счёта";
            case REGISTRY_INCLUDED -> "дата включения рейса в согласованный реестр";
            case DOCUMENT_PACKAGE_RECEIVED -> "дата получения полного комплекта документов";
            case LATEST_ACT_OR_DOCUMENT_PACKAGE ->
                "более поздняя из даты подписания акта и даты получения полного комплекта документов";
            case ACT_SIGNED_REQUIRES_DOCUMENT_PACKAGE ->
                "дата подписания акта при обязательном условии получения полного комплекта документов";
        };
    }

    private AiGenerateClaimRequest.TermDayType mapTermDayType(TermDayType type) {
        if (type == null) return null;
        return switch (type) {
            case CALENDAR_DAYS -> AiGenerateClaimRequest.TermDayType.CALENDAR_DAYS;
            case WORKING_DAYS -> AiGenerateClaimRequest.TermDayType.WORKING_DAYS;
            case BANKING_DAYS -> AiGenerateClaimRequest.TermDayType.BANKING_DAYS;
        };
    }

    private String termDayTypeLabel(TermDayType type) {
        if (type == null) return "календарных дней";
        return switch (type) {
            case CALENDAR_DAYS -> "календарных дней";
            case WORKING_DAYS -> "рабочих дней";
            case BANKING_DAYS -> "банковских дней";
        };
    }

    private String penaltyCapLabel(ClaimContract contract) {
        if (contract.getPenaltyCapPercent() == null || contract.getPenaltyCapBase() == null) return "";
        String base = switch (contract.getPenaltyCapBase()) {
            case PRINCIPAL_DEBT -> "основного долга";
            case OUTSTANDING_DEBT -> "непогашенной задолженности";
            case SHIPMENT_COST -> "стоимости соответствующей перевозки";
            case INVOICE_AMOUNT -> "суммы соответствующего счёта";
        };
        return "; договорное ограничение: не более "
            + contract.getPenaltyCapPercent().stripTrailingZeros().toPlainString()
            + "% от " + base;
    }

    private String penaltyTypeLabel(ru.sber.cargotech.claim.enums.PenaltyType penaltyType) {
        return switch (penaltyType) {
            case CONTRACT_PENALTY -> "договорная неустойка";
            case ARTICLE_395 -> "проценты по статье 395 ГК РФ";
            case NONE -> "не начисляется";
        };
    }

    private List<AiGenerateClaimRequest.LegalContextItem> defaultLegalContext(ClaimCalculation calculation) {
        List<AiGenerateClaimRequest.LegalContextItem> items = new ArrayList<>(List.of(
                new AiGenerateClaimRequest.LegalContextItem(
                        "fallback-payment-gk-309",
                        "ГК РФ",
                        "309",
                        "Общее основание требования надлежащего исполнения обязательства.",
                        "Обязательства должны исполняться надлежащим образом в соответствии с условиями обязательства и требованиями закона.",
                        "ГК РФ, ст. 309",
                        "2026-08-12",
                        "Допускается для действующего договорного денежного обязательства."
                ),
                new AiGenerateClaimRequest.LegalContextItem(
                        "fallback-payment-gk-314-1",
                        "ГК РФ",
                        "314 п. 1",
                        "Исполнение обязательства в определённый договором день или период.",
                        "Если обязательство позволяет определить день или период исполнения, оно подлежит исполнению в этот день или в пределах такого периода.",
                        "ГК РФ, п. 1 ст. 314",
                        "2026-08-12",
                        "Применяется при подтверждённом условии договора о сроке оплаты."
                )
        ));

        if (calculation.getPenaltyType() == ru.sber.cargotech.claim.enums.PenaltyType.CONTRACT_PENALTY) {
            items.add(new AiGenerateClaimRequest.LegalContextItem(
                    "fallback-penalty-gk-330",
                    "ГК РФ",
                    "330",
                    "Правовая квалификация договорной неустойки.",
                    "Неустойкой признаётся определённая законом или договором денежная сумма, подлежащая уплате при неисполнении или ненадлежащем исполнении обязательства, в том числе при просрочке.",
                    "ГК РФ, ст. 330",
                    "2026-08-12",
                    "Применяется только вместе с подтверждённым договорным условием о неустойке; размер и ставка берутся из договора и backend-расчёта."
            ));
        } else if (calculation.getPenaltyType() == ru.sber.cargotech.claim.enums.PenaltyType.ARTICLE_395) {
            items.add(new AiGenerateClaimRequest.LegalContextItem(
                    "fallback-interest-gk-395",
                    "ГК РФ",
                    "395",
                    "Основание начисления процентов при отсутствии договорной неустойки.",
                    "За неправомерное удержание денежных средств начисляются проценты; размер определяется ключевой ставкой Банка России, действовавшей в соответствующие периоды.",
                    "ГК РФ, ст. 395",
                    "2026-08-12",
                    "Применяется при просрочке денежного обязательства, когда договорная неустойка не установлена."
            ));
        }
        return List.copyOf(items);
    }

    private String buildRoute(ClaimShipment shipment) {
        if (shipment.getRouteFrom() == null || shipment.getRouteFrom().isBlank()) {
            return shipment.getRouteTo();
        }
        if (shipment.getRouteTo() == null || shipment.getRouteTo().isBlank()) {
            return shipment.getRouteFrom();
        }
        return shipment.getRouteFrom() + " — " + shipment.getRouteTo();
    }

    private String asString(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

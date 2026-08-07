package ru.sber.cargotech.claim.mapper;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;
import ru.sber.cargotech.claim.enums.PaymentStartEvent;
import ru.sber.cargotech.claim.security.CurrentClaimUser;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
        AiGenerateClaimRequest.ClaimType claimType = mapClaimType(claim);

        return new AiGenerateClaimRequest(
                new AiGenerateClaimRequest.CaseFacts(
                        claim.getId().toString(),
                        claim.getClaimNumber(),
                        claimType,
                        mapParty(creditor),
                        mapParty(debtor),
                        new AiGenerateClaimRequest.ContractFacts(
                                contract.getNumber(),
                                asString(contract.getSignedAt()),
                                contract.getClaimResponseDays()
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
                        asString(LocalDate.now()),
                        mapSignatory(currentUser)
                ),
                new AiGenerateClaimRequest.BackendCalculation(
                        calculation.getRemainingDebt(),
                        mapPenaltyType(calculation),
                        calculation.getPenaltyRate() == null
                                ? null
                                : calculation.getPenaltyRate().toPlainString(),
                        calculation.getOverdueDays(),
                        calculation.getPenaltyAmount(),
                        calculation.getTotalAmount(),
                        shipment.getCurrency(),
                        calculation.getFormula()
                ),
                buildStructuredContractContext(contract),
                defaultLegalContext(),
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
                                "Подпись представителя кредитора"
                        )
                ),
                List.of(),
                new AiGenerateClaimRequest.RagOptions(
                        true,
                        contract.getId().toString(),
                        contract.getClientId().toString()
                )
        );
    }

    private AiGenerateClaimRequest.ClaimType mapClaimType(ClaimEntity claim) {
        return switch (claim.getClaimType()) {
            case PAYMENT_DELAY -> AiGenerateClaimRequest.ClaimType.PAYMENT_DELAY;
        };
    }

    private AiGenerateClaimRequest.Party mapParty(ClaimParty party) {
        return new AiGenerateClaimRequest.Party(
                party.getName(),
                party.getInn(),
                party.getLegalAddress()
        );
    }

    private AiGenerateClaimRequest.SignatoryFacts mapSignatory(CurrentClaimUser currentUser) {
        if (currentUser == null || currentUser.fullName().isBlank()) {
            return null;
        }
        String position = currentUser.hasRole("LAWYER")
                ? "Юрист"
                : "Представитель кредитора";
        return new AiGenerateClaimRequest.SignatoryFacts(currentUser.fullName(), position);
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

    private List<AiGenerateClaimRequest.ContractContextChunk> buildStructuredContractContext(ClaimContract contract) {
        List<AiGenerateClaimRequest.ContractContextChunk> context = new ArrayList<>();
        String prefix = "contract-card-" + contract.getId();

        if (contract.getPaymentDays() != null && contract.getPaymentDays() >= 0) {
            context.add(new AiGenerateClaimRequest.ContractContextChunk(
                    prefix + "-payment-term",
                    null,
                    "Структурированные условия оплаты",
                    "Оплата должна быть произведена в течение " + contract.getPaymentDays()
                            + " календарных дней. Начало отсчёта срока: "
                            + paymentStartEventLabel(contract.getPaymentStartEvent())
                            + ". Номер пункта договора в карточке не указан; в тексте следует писать «согласно условиям договора»."
            ));
        }

        if (contract.getPenaltyType() != null) {
            String rate = contract.getPenaltyRate() == null
                    ? "ставка в карточке не указана"
                    : "ставка " + contract.getPenaltyRate().stripTrailingZeros().toPlainString() + "%";
            context.add(new AiGenerateClaimRequest.ContractContextChunk(
                    prefix + "-penalty",
                    null,
                    "Структурированные условия ответственности",
                    "Вид ответственности за просрочку: " + penaltyTypeLabel(contract.getPenaltyType())
                            + "; " + rate
                            + ". Номер пункта договора в карточке не указан; запрещено выдумывать номер пункта."
            ));
        }

        if (contract.getClaimResponseDays() != null && contract.getClaimResponseDays() > 0) {
            context.add(new AiGenerateClaimRequest.ContractContextChunk(
                    prefix + "-pretrial-response",
                    null,
                    "Структурированный срок ответа на претензию",
                    "Срок направления ответа на претензию: " + contract.getClaimResponseDays()
                            + " календарных дней с даты получения претензии. Номер пункта договора в карточке не указан."
            ));
        }

        return List.copyOf(context);
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
        };
    }

    private String penaltyTypeLabel(ru.sber.cargotech.claim.enums.PenaltyType penaltyType) {
        return switch (penaltyType) {
            case CONTRACT_PENALTY -> "договорная неустойка";
            case ARTICLE_395 -> "проценты по статье 395 ГК РФ";
            case NONE -> "не начисляется";
        };
    }

    private List<AiGenerateClaimRequest.LegalContextItem> defaultLegalContext() {
        return List.of(
                new AiGenerateClaimRequest.LegalContextItem(
                        "fallback-payment-gk-309",
                        "ГК РФ",
                        "309",
                        "Общее основание требования надлежащего исполнения обязательства.",
                        "Обязательства должны исполняться надлежащим образом в соответствии с условиями обязательства и требованиями закона.",
                        "ГК РФ, ст. 309",
                        "2026-08-07",
                        "Допускается для действующего договорного денежного обязательства."
                ),
                new AiGenerateClaimRequest.LegalContextItem(
                        "fallback-payment-gk-314-1",
                        "ГК РФ",
                        "314 п. 1",
                        "Исполнение обязательства в определённый договором день или период.",
                        "Если обязательство позволяет определить день или период исполнения, оно подлежит исполнению в этот день или в пределах такого периода.",
                        "ГК РФ, п. 1 ст. 314",
                        "2026-08-07",
                        "Применяется при подтверждённом условии договора о сроке оплаты."
                )
        );
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
}

package ru.sber.cargotech.claim.mapper;

import org.springframework.stereotype.Component;
import ru.sber.cargotech.claim.client.dto.AiGenerateClaimRequest;
import ru.sber.cargotech.claim.entity.ClaimCalculation;
import ru.sber.cargotech.claim.entity.ClaimContract;
import ru.sber.cargotech.claim.entity.ClaimEntity;
import ru.sber.cargotech.claim.entity.ClaimParty;
import ru.sber.cargotech.claim.entity.ClaimShipment;

import java.time.LocalDate;
import java.util.List;

@Component
public class ClaimAiRequestMapper {

    public AiGenerateClaimRequest map(
            ClaimEntity claim,
            ClaimParty creditor,
            ClaimParty debtor,
            ClaimContract contract,
            ClaimShipment shipment,
            ClaimCalculation calculation
    ) {
        AiGenerateClaimRequest.ClaimType claimType = mapClaimType(claim);

        return new AiGenerateClaimRequest(
                new AiGenerateClaimRequest.CaseFacts(
                        claim.getId().toString(),
                        claimType,
                        mapParty(creditor),
                        mapParty(debtor),
                        new AiGenerateClaimRequest.ContractFacts(
                                contract.getNumber(),
                                asString(contract.getSignedAt())
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
                        asString(LocalDate.now())
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
                List.of(),
                defaultLegalContext(),
                new AiGenerateClaimRequest.TemplateContext(
                        "claim-default-v1",
                        "Шаблон претензии CargoTech",
                        claimType,
                        List.of(
                                "Реквизиты сторон",
                                "Обстоятельства нарушения",
                                "Расчёт задолженности и неустойки",
                                "Правовое обоснование",
                                "Требования кредитора",
                                "Срок исполнения",
                                "Приложения"
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

    private List<AiGenerateClaimRequest.LegalContextItem> defaultLegalContext() {
        return List.of(
                new AiGenerateClaimRequest.LegalContextItem(
                        "ГК РФ",
                        "309",
                        "Надлежащее исполнение обязательств"
                ),
                new AiGenerateClaimRequest.LegalContextItem(
                        "ГК РФ",
                        "310",
                        "Недопустимость одностороннего отказа от обязательства"
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

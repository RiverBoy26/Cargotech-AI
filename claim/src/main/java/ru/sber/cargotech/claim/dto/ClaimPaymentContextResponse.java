package ru.sber.cargotech.claim.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimPaymentContextResponse(
        UUID claimId,
        UUID shipmentId,
        String claimNumber,
        String debtorInn,
        String shipmentOrderNumber,
        BigDecimal serviceAmount,
        BigDecimal calculatedPaidAmount,
        BigDecimal remainingPrincipalAmount,
        BigDecimal remainingPenaltyAmount,
        String status
) {
}

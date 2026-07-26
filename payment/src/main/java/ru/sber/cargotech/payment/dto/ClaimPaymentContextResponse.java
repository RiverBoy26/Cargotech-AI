package ru.sber.cargotech.payment.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ClaimPaymentContextResponse(
        UUID claimId,
        UUID shipmentId,
        String claimNumber,
        String debtorInn,
        String shipmentOrderNumber,
        BigDecimal serviceAmount,
        String status
) {
}

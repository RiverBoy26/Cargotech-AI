package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentCheckStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentStateResponse(
        UUID claimId,
        UUID shipmentId,
        BigDecimal serviceAmount,
        BigDecimal paidAmount,
        BigDecimal remainingAmount,
        PaymentCheckStatus paymentStatus
) {
}

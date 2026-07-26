package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentCheckStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record MarkPaidResponse(
    UUID claimId,
    UUID paymentId,
    UUID paymentCheckId,
    UUID outboxEventId,
    BigDecimal paidAmount,
    BigDecimal remainingAmount,
    PaymentCheckStatus paymentStatus
) {
}

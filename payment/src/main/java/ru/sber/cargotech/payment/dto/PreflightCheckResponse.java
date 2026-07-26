package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentCheckStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PreflightCheckResponse(
    UUID checkId,
    UUID claimId,
    UUID shipmentId,
    BigDecimal serviceAmount,
    BigDecimal paidAmount,
    BigDecimal remainingAmount,
    PaymentCheckStatus paymentStatus,
    LocalDate lastPaymentDate,
    List<UUID> paymentIds,
    boolean canSend,
    String recommendedAction,
    OffsetDateTime checkedAt
) {
}

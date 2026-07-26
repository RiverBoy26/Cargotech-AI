package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentCheckStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ClaimPaymentsResponse(
    UUID claimId,
    UUID shipmentId,
    String claimNumber,
    BigDecimal serviceAmount,
    BigDecimal paidAmount,
    BigDecimal remainingAmount,
    PaymentCheckStatus paymentStatus,
    LocalDate lastPaymentDate,
    List<PaymentResponse> payments
) {
}

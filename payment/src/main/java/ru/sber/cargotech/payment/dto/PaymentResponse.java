package ru.sber.cargotech.payment.dto;

import ru.sber.cargotech.payment.enums.PaymentSourceSystem;
import ru.sber.cargotech.payment.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentResponse(
    UUID id,
    PaymentSourceSystem sourceSystem,
    String externalPaymentId,
    String paymentNumber,
    LocalDate paymentDate,
    String payerInn,
    String payerName,
    String recipientInn,
    String recipientName,
    BigDecimal amount,
    String currency,
    String purpose,
    PaymentStatus status,
    BigDecimal matchedAmount,
    BigDecimal availableAmount
) {
}

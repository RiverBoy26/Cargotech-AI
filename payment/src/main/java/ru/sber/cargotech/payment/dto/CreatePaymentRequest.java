package ru.sber.cargotech.payment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreatePaymentRequest(
    @Size(max = 255) String externalPaymentId,
    @Size(max = 128) String paymentNumber,
    @NotNull LocalDate paymentDate,
    @Size(max = 12) String payerInn,
    @Size(max = 500) String payerName,
    @Size(max = 12) String recipientInn,
    @Size(max = 500) String recipientName,
    @NotNull BigDecimal amount,
    @Size(max = 10) String currency,
    String purpose
) {
}

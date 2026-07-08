package ru.sber.cargotech.payment.dto;

import java.time.OffsetDateTime;

public record PaymentErrorResponse(
    String code,
    String message,
    OffsetDateTime timestamp
) {
}

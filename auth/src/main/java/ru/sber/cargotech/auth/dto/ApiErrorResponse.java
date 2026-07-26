package ru.sber.cargotech.auth.dto;

import java.time.OffsetDateTime;

public record ApiErrorResponse(
    String code,
    String message,
    OffsetDateTime timestamp
) {
}

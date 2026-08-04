package ru.sber.cargotech.document.dto;

import java.time.OffsetDateTime;

public record DocumentErrorResponse(
    String code,
    String message,
    OffsetDateTime timestamp
) {
}

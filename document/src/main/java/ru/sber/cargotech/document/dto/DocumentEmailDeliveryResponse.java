package ru.sber.cargotech.document.dto;

import ru.sber.cargotech.document.enums.EmailDeliveryStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentEmailDeliveryResponse(
    UUID id,
    UUID documentId,
    String recipient,
    String cc,
    String subject,
    EmailDeliveryStatus status,
    int attemptCount,
    String errorMessage,
    UUID sentBy,
    OffsetDateTime createdAt,
    OffsetDateTime sentAt,
    OffsetDateTime updatedAt
) {
}

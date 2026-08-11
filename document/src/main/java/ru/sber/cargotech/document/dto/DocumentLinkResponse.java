package ru.sber.cargotech.document.dto;

import ru.sber.cargotech.document.enums.DocumentEntityType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentLinkResponse(
    UUID id,
    UUID documentId,
    DocumentEntityType entityType,
    UUID entityId,
    String linkType,
    UUID createdBy,
    OffsetDateTime createdAt
) {
}

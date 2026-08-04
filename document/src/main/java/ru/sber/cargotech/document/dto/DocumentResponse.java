package ru.sber.cargotech.document.dto;

import ru.sber.cargotech.document.enums.DocumentSource;
import ru.sber.cargotech.document.enums.DocumentStatus;
import ru.sber.cargotech.document.enums.DocumentType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record DocumentResponse(
    UUID id,
    UUID organizationId,
    DocumentType documentType,
    String documentNumber,
    LocalDate documentDate,
    String description,
    DocumentSource source,
    DocumentStatus status,
    UUID createdBy,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    DocumentFileResponse file,
    List<DocumentLinkResponse> links
) {
}

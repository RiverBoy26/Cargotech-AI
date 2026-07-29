package ru.sber.cargotech.document.dto;

import ru.sber.cargotech.document.enums.DocumentType;

import java.time.LocalDate;
import java.util.UUID;

public record StoreGeneratedFileCommand(
    UUID organizationId,
    UUID userId,
    byte[] content,
    String filename,
    String contentType,
    DocumentType documentType,
    String documentNumber,
    LocalDate documentDate,
    String description
) {
}

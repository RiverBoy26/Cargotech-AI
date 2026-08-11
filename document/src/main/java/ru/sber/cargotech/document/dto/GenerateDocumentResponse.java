package ru.sber.cargotech.document.dto;

import ru.sber.cargotech.document.enums.GeneratedDocumentType;
import ru.sber.cargotech.document.enums.GenerationStatus;

import java.util.UUID;

public record GenerateDocumentResponse(
    UUID generationLogId,
    UUID documentId,
    UUID fileId,
    UUID claimId,
    UUID templateId,
    UUID templateVersionId,
    GeneratedDocumentType outputType,
    GenerationStatus status,
    String downloadUrl
) {
}

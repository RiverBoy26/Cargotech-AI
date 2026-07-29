package ru.sber.cargotech.document.dto;

import jakarta.validation.constraints.NotNull;
import ru.sber.cargotech.document.enums.GeneratedDocumentType;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record GenerateClaimDocumentRequest(
    UUID templateId,
    UUID templateVersionId,
    String templateCode,
    @NotNull UUID claimId,
    GeneratedDocumentType outputType,
    String documentNumber,
    LocalDate documentDate,
    String description,
    String claimText,
    Map<String, Object> data
) {
}

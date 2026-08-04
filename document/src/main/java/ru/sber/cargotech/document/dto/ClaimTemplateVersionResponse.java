package ru.sber.cargotech.document.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ClaimTemplateVersionResponse(
    UUID id,
    UUID templateId,
    int versionNumber,
    String contentSha256,
    String encryptionKeyId,
    String encryptionAlgorithm,
    String contentFormat,
    List<String> variables,
    String changeComment,
    boolean active,
    UUID createdBy,
    OffsetDateTime createdAt
) {
}

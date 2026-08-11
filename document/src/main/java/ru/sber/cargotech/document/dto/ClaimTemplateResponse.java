package ru.sber.cargotech.document.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ClaimTemplateResponse(
    UUID id,
    UUID organizationId,
    String code,
    String name,
    String claimType,
    UUID clientId,
    String description,
    boolean defaultTemplate,
    int priority,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    List<ClaimTemplateVersionResponse> versions
) {
}

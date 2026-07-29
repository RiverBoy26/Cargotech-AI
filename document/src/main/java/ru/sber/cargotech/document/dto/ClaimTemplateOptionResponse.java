package ru.sber.cargotech.document.dto;

import java.util.UUID;

public record ClaimTemplateOptionResponse(
    UUID templateId,
    UUID activeVersionId,
    String code,
    String name,
    String claimType,
    UUID clientId,
    boolean clientSpecific,
    boolean defaultTemplate,
    int priority,
    boolean recommended
) {
}

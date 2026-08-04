package ru.sber.cargotech.document.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateClaimTemplateRequest(
    @NotBlank String code,
    @NotBlank String name,
    @NotBlank String claimType,
    UUID clientId,
    String description,
    Boolean defaultTemplate,
    Integer priority
) {
}

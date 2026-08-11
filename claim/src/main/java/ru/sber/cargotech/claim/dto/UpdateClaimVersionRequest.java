package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateClaimVersionRequest(
    @NotBlank String content,
    String comment,
    Boolean finalVersion
) {
}

package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;
import ru.sber.cargotech.claim.enums.ClaimVersionSource;

import java.util.UUID;

public record CreateClaimVersionRequest(
    ClaimVersionSource source,
    UUID baseVersionId,
    @NotBlank String content,
    String comment,
    Boolean finalVersion
) {
}

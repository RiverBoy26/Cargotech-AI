package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ContractIntakeRequest(
    @NotNull UUID clientId,
    @NotNull UUID documentId
) {
}

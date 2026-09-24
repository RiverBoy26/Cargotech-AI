package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;

public record ValidationOverrideRequest(@NotBlank String reason) {
}

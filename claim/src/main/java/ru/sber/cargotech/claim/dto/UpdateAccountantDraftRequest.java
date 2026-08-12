package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAccountantDraftRequest(String reason, @NotBlank String text) {
}

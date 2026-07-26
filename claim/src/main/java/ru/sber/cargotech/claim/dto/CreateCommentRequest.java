package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCommentRequest(
    @NotBlank String text
) {
}

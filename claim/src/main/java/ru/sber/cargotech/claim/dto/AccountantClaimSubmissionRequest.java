package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountantClaimSubmissionRequest(
    @NotBlank(message = "укажите основание претензии")
    @Size(max = 10_000, message = "основание претензии слишком длинное")
    String reason,
    @NotBlank(message = "укажите текст претензии")
    @Size(max = 100_000, message = "текст претензии слишком длинный")
    String text
) {
}

package ru.sber.cargotech.document.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateClaimTemplateVersionRequest(
    @NotBlank String content,
    String changeComment,
    boolean activate
) {
}

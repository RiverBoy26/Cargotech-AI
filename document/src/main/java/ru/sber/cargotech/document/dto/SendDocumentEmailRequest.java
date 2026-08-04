package ru.sber.cargotech.document.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendDocumentEmailRequest(
    @NotBlank @Email String to,
    @Email String cc,
    @Size(max = 500) String subject,
    @Size(max = 10000) String message
) {
}

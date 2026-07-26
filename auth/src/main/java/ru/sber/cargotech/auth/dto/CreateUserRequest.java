package ru.sber.cargotech.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreateUserRequest(
    UUID organizationId,
    @NotBlank @Size(max = 255) String fullName,
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(min = 8, max = 128) String password,
    @NotEmpty Set<@NotBlank String> roles
) {
}

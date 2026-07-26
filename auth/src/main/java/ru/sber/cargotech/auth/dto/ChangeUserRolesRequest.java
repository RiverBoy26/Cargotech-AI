package ru.sber.cargotech.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record ChangeUserRolesRequest(
    @NotEmpty Set<@NotBlank String> roles
) {
}

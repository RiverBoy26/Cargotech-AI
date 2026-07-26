package ru.sber.cargotech.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @Size(min = 1, max = 255) String fullName,
    @Email @Size(max = 320) String email
) {
}

package ru.sber.cargotech.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @Size(min = 1, max = 100) String firstName,
    @Size(min = 1, max = 100) String lastName,
    @Size(max = 100) String middleName,
    @Email @Size(max = 320) String email
) {
}

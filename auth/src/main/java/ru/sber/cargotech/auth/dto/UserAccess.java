package ru.sber.cargotech.auth.dto;

import java.util.Set;
import java.util.UUID;

public record UserAccess(
        UUID userId,
        UUID organizationId,
        String firstName,
        String lastName,
        String middleName,
        String email,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}

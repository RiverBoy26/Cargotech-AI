package ru.sber.cargotech.auth.security;

import java.util.Set;
import java.util.UUID;

public record CurrentUser(
    UUID userId,
    UUID organizationId,
    Set<String> roles,
    Set<String> permissions
) {
    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}

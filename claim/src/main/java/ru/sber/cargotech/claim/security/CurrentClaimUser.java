package ru.sber.cargotech.claim.security;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public record CurrentClaimUser(
        UUID userId,
        UUID organizationId,
        String firstName,
        String lastName,
        String middleName,
        List<String> roles
) {
    public CurrentClaimUser(UUID userId, UUID organizationId) {
        this(userId, organizationId, null, null, null, List.of());
    }

    public CurrentClaimUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public String fullName() {
        return String.join(" ", Stream.of(lastName, firstName, middleName)
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    public boolean hasRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String expected = role.trim().toUpperCase(Locale.ROOT);
        return roles.stream()
                .filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }
}

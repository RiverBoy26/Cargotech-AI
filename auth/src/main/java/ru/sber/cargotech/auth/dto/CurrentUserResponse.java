package ru.sber.cargotech.auth.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record CurrentUserResponse(
    UUID id,
    UUID organizationId,
    String fullName,
    String email,
    boolean active,
    OffsetDateTime blockedAt,
    OffsetDateTime lastLoginAt,
    Set<String> roles,
    Set<String> permissions
) {
}

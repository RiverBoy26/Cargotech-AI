package ru.sber.cargotech.auth.dto;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record TokenResponse(
    String tokenType,
    String accessToken,
    OffsetDateTime accessTokenExpiresAt,
    String refreshToken,
    OffsetDateTime refreshTokenExpiresAt,
    UUID userId,
    UUID organizationId,
    Set<String> roles,
    Set<String> permissions
) {
}

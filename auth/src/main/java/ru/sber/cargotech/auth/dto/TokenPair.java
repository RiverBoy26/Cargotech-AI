package ru.sber.cargotech.auth.dto;

import java.time.OffsetDateTime;

public record TokenPair(
        String accessToken,
        OffsetDateTime accessTokenExpiresAt,
        String refreshToken,
        OffsetDateTime refreshTokenExpiresAt
) {
}

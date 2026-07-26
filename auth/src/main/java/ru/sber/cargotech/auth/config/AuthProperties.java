package ru.sber.cargotech.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
    Jwt jwt,
    Password password,
    Bootstrap bootstrap
) {
    public record Jwt(
        String issuer,
        String secretBase64,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
    ) {
    }

    public record Password(int bcryptStrength) {
    }

    public record Bootstrap(
        boolean enabled,
        String email,
        String password,
        String fullName,
        String organizationName,
        String organizationInn
    ) {
    }
}

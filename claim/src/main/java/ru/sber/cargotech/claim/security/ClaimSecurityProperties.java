package ru.sber.cargotech.claim.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "claim.security")
public record ClaimSecurityProperties(
        String jwtSecretBase64,
        String jwtIssuer
) {
}
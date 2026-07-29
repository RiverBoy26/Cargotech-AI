package ru.sber.cargotech.document.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document.security")
public record DocumentSecurityProperties(
    String jwtSecretBase64,
    String jwtIssuer
) {
}

package ru.sber.cargotech.payment.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.security")
public record PaymentSecurityProperties(
        String jwtSecretBase64,
        String jwtIssuer
) {
}
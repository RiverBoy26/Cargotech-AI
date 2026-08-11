package ru.sber.cargotech.payment.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.claim")
public record ClaimClientProperties(
        String baseUrl
) {
}

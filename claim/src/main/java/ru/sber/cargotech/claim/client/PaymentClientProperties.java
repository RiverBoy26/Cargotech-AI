package ru.sber.cargotech.claim.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "services.payment")
public record PaymentClientProperties(
        String baseUrl
) {
}

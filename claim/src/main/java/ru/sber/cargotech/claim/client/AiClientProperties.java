package ru.sber.cargotech.claim.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "services.ai")
public record AiClientProperties(
        String baseUrl,
        String internalApiKey,
        Duration connectTimeout,
        Duration readTimeout,
        Duration ragReadTimeout
) {
}

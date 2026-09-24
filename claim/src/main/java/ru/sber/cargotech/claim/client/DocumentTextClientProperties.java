package ru.sber.cargotech.claim.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "services.document")
public record DocumentTextClientProperties(
    String baseUrl,
    String internalApiKey,
    Duration connectTimeout,
    Duration readTimeout
) {}

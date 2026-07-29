package ru.sber.cargotech.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document.mail")
public record DocumentMailProperties(
    String from,
    String fromName
) {
}

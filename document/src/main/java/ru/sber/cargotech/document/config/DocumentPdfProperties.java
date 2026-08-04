package ru.sber.cargotech.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document.pdf")
public record DocumentPdfProperties(
    String fontPath
) {
}

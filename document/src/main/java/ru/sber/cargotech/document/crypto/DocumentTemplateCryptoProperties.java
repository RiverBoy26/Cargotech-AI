package ru.sber.cargotech.document.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "document.templates")
public record DocumentTemplateCryptoProperties(
    String encryptionKeyBase64,
    String encryptionKeyId
) {
}

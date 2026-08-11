package ru.sber.cargotech.document.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.sber.cargotech.document.enums.StorageProvider;

@ConfigurationProperties(prefix = "document.storage")
public record DocumentStorageProperties(
    StorageProvider provider,
    String localRootPath,
    String bucketName
) {
}

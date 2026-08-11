package ru.sber.cargotech.document.storage;

import ru.sber.cargotech.document.enums.StorageProvider;

public record StoredFile(
    StorageProvider provider,
    String bucketName,
    String storageKey,
    String originalName,
    String contentType,
    long sizeBytes,
    String checksum
) {
}

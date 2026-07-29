package ru.sber.cargotech.document.dto;

import ru.sber.cargotech.document.enums.StorageProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentFileResponse(
    UUID id,
    StorageProvider storageProvider,
    String bucketName,
    String storageKey,
    String originalName,
    String contentType,
    long sizeBytes,
    String checksum,
    UUID uploadedBy,
    OffsetDateTime uploadedAt
) {
}

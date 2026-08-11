package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ClaimVersionSource;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimVersionResponse(
    UUID id,
    UUID claimId,
    Integer versionNumber,
    ClaimVersionSource source,
    UUID baseVersionId,
    String content,
    String comment,
    boolean finalVersion,
    UUID createdBy,
    OffsetDateTime createdAt
) {
}

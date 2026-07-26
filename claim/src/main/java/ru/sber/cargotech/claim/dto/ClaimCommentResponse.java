package ru.sber.cargotech.claim.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimCommentResponse(
    UUID id,
    UUID claimId,
    UUID authorId,
    String text,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}

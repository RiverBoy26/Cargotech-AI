package ru.sber.cargotech.claim.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimCommentResponse(
    UUID id,
    UUID claimId,
    UUID authorId,
    String authorName,
    String text,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}

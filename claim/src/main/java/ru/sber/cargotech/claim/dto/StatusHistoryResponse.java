package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ClaimStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StatusHistoryResponse(
    UUID id,
    UUID claimId,
    ClaimStatus previousStatus,
    ClaimStatus newStatus,
    String reason,
    UUID changedBy,
    String changedByLabel,
    OffsetDateTime changedAt
) {
    public StatusHistoryResponse(
        UUID id,
        UUID claimId,
        ClaimStatus previousStatus,
        ClaimStatus newStatus,
        String reason,
        UUID changedBy,
        OffsetDateTime changedAt
    ) {
        this(id, claimId, previousStatus, newStatus, reason, changedBy, null, changedAt);
    }
}

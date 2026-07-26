package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimListItemResponse(
    UUID id,
    String claimNumber,
    ClaimType claimType,
    ClaimStatus status,
    UUID shipmentId,
    String shipmentNumber,
    UUID creditorId,
    String creditorName,
    UUID debtorId,
    String debtorName,
    UUID assignedLawyerId,
    BigDecimal principalDebt,
    BigDecimal penaltyAmount,
    BigDecimal totalAmount,
    Integer overdueDays,
    Integer paymentDays,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}

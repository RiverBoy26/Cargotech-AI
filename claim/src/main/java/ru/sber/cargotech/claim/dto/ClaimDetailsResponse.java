package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ClaimType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimDetailsResponse(
    UUID id,
    UUID organizationId,
    String claimNumber,
    ClaimType claimType,
    ClaimStatus status,
    String reason,
    UUID shipmentId,
    String shipmentNumber,
    UUID contractId,
    String contractNumber,
    UUID creditorId,
    String creditorName,
    UUID debtorId,
    String debtorName,
    BigDecimal principalDebt,
    BigDecimal penaltyAmount,
    BigDecimal totalAmount,
    boolean nonPaymentConfirmed,
    OffsetDateTime nonPaymentConfirmedAt,
    UUID nonPaymentConfirmedBy,
    String nonPaymentConfirmationComment,
    UUID assignedLawyerId,
    UUID finalVersionId,
    UUID lastPaymentCheckId,
    OffsetDateTime approvedAt,
    UUID approvedBy,
    OffsetDateTime sentAt,
    OffsetDateTime paidAt,
    OffsetDateTime cancelledAt,
    String cancellationReasonCode,
    String cancellationReason,
    OffsetDateTime escalatedAt,
    OffsetDateTime createdAt,
    UUID createdBy,
    OffsetDateTime updatedAt,
    UUID updatedBy
) {
}

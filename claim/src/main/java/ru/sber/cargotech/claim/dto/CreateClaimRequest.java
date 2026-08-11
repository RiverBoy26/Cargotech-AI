package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.sber.cargotech.claim.enums.ClaimType;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateClaimRequest(
    @Size(max = 128) String claimNumber,
    @NotNull UUID shipmentId,
    UUID creditorId,
    UUID debtorId,
    ClaimType claimType,
    String reason,
    @PositiveOrZero BigDecimal principalDebt,
    @PositiveOrZero BigDecimal penaltyAmount,
    UUID assignedLawyerId,
    Boolean nonPaymentConfirmed,
    String nonPaymentConfirmationComment,
    String draftContent
) {
}

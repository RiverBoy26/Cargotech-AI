package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateClaimRequest(
    String reason,
    @PositiveOrZero BigDecimal principalDebt,
    @PositiveOrZero BigDecimal penaltyAmount,
    UUID assignedLawyerId,
    Boolean nonPaymentConfirmed,
    String nonPaymentConfirmationComment
) {
}

package ru.sber.cargotech.claim.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateLastPaymentCheckRequest(
        @NotNull UUID checkId,
        @NotNull @PositiveOrZero BigDecimal remainingPrincipalAmount,
        @NotNull @PositiveOrZero BigDecimal remainingPenaltyAmount
) {
}

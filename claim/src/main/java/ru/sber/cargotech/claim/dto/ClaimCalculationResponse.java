package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ClaimCalculationResponse(
    UUID id,
    UUID claimId,
    Integer calculationVersion,
    BigDecimal principalDebt,
    BigDecimal paidAmount,
    BigDecimal remainingDebt,
    LocalDate overdueStartDate,
    LocalDate calculationDate,
    Integer overdueDays,
    PenaltyType penaltyType,
    BigDecimal penaltyRate,
    BigDecimal penaltyAmount,
    BigDecimal totalAmount,
    String formula,
    UUID createdBy,
    OffsetDateTime createdAt
) {
}

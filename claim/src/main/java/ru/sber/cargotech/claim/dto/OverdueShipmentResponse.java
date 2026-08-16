package ru.sber.cargotech.claim.dto;

import ru.sber.cargotech.claim.enums.ClaimStatus;
import ru.sber.cargotech.claim.enums.ShipmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record OverdueShipmentResponse(
    UUID shipmentId,
    String shipmentNumber,
    String clientName,
    String clientInn,
    String expeditorName,
    BigDecimal shipmentAmount,
    BigDecimal paidAmount,
    BigDecimal remainingDebt,
    BigDecimal remainingPrincipalDebt,
    BigDecimal penaltyAmount,
    BigDecimal totalAmount,
    String currency,
    LocalDate paymentDeadline,
    LocalDate overdueStartDate,
    Integer overdueDays,
    ShipmentStatus shipmentStatus,
    UUID claimId,
    String claimNumber,
    ClaimStatus claimStatus,
    boolean nonPaymentConfirmed,
    UUID finalVersionId
) {
}

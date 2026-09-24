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
    String nonPaymentConfirmationComment,
    String claimNumber,
    String recipientName,
    String recipientEmail,
    String recipientAddress,
    String bankDetails,
    @PositiveOrZero Integer responseDeadlineDays,
    String signerFullName,
    String signerPosition,
    String signerAuthority,
    String text
) {
    public UpdateClaimRequest(
        String reason,
        BigDecimal principalDebt,
        BigDecimal penaltyAmount,
        UUID assignedLawyerId,
        Boolean nonPaymentConfirmed,
        String nonPaymentConfirmationComment
    ) {
        this(
            reason,
            principalDebt,
            penaltyAmount,
            assignedLawyerId,
            nonPaymentConfirmed,
            nonPaymentConfirmationComment,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}

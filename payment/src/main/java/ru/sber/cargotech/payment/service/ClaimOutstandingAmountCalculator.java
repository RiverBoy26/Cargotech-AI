package ru.sber.cargotech.payment.service;

import ru.sber.cargotech.payment.repository.ClaimPaymentData;

import java.math.BigDecimal;

final class ClaimOutstandingAmountCalculator {

    private ClaimOutstandingAmountCalculator() {
    }

    static BigDecimal calculate(
            ClaimPaymentData claim,
            BigDecimal currentPaidAmount
    ) {
        return calculateBreakdown(claim, currentPaidAmount).total();
    }

    static OutstandingBreakdown calculateBreakdown(
            ClaimPaymentData claim,
            BigDecimal currentPaidAmount
    ) {
        BigDecimal remainingPrincipalAtCalculation = safe(claim.remainingPrincipalAmount());
        BigDecimal remainingPenaltyAtCalculation = safe(claim.remainingPenaltyAmount());
        BigDecimal paidAfterCalculation = safe(currentPaidAmount)
                .subtract(safe(claim.calculatedPaidAmount()))
                .max(BigDecimal.ZERO);
        BigDecimal paidPrincipalAfterCalculation = paidAfterCalculation
                .min(remainingPrincipalAtCalculation);
        BigDecimal remainingPrincipal = remainingPrincipalAtCalculation
                .subtract(paidPrincipalAfterCalculation)
                .max(BigDecimal.ZERO);
        BigDecimal paidPenaltyAfterCalculation = paidAfterCalculation
                .subtract(paidPrincipalAfterCalculation)
                .max(BigDecimal.ZERO)
                .min(remainingPenaltyAtCalculation);
        BigDecimal remainingPenalty = remainingPenaltyAtCalculation
                .subtract(paidPenaltyAfterCalculation)
                .max(BigDecimal.ZERO);

        return new OutstandingBreakdown(
                remainingPrincipal,
                remainingPenalty,
                remainingPrincipal.add(remainingPenalty)
        );
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    record OutstandingBreakdown(
            BigDecimal remainingPrincipal,
            BigDecimal remainingPenalty,
            BigDecimal total
    ) {
    }
}

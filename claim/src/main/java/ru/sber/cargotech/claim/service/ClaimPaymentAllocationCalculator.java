package ru.sber.cargotech.claim.service;

import java.math.BigDecimal;

final class ClaimPaymentAllocationCalculator {

    private ClaimPaymentAllocationCalculator() {
    }

    static AllocationResult allocate(
            BigDecimal principalDebt,
            BigDecimal accruedPenalty,
            BigDecimal paidAmount
    ) {
        BigDecimal principal = safe(principalDebt);
        BigDecimal penalty = safe(accruedPenalty);
        BigDecimal paid = safe(paidAmount).max(BigDecimal.ZERO);

        BigDecimal paidPrincipal = paid.min(principal);
        BigDecimal remainingPrincipal = principal.subtract(paidPrincipal).max(BigDecimal.ZERO);
        BigDecimal paidPenalty = paid.subtract(paidPrincipal)
                .max(BigDecimal.ZERO)
                .min(penalty);
        BigDecimal remainingPenalty = penalty.subtract(paidPenalty).max(BigDecimal.ZERO);

        return new AllocationResult(remainingPrincipal, paidPenalty, remainingPenalty);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    record AllocationResult(
            BigDecimal remainingPrincipal,
            BigDecimal paidPenalty,
            BigDecimal remainingPenalty
    ) {
    }
}

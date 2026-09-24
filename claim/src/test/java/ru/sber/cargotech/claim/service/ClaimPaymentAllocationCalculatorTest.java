package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimPaymentAllocationCalculatorTest {

    @Test
    void paymentDoesNotReducePenaltyUntilPrincipalIsPaid() {
        var result = allocate("50000.00");

        assertEquals(new BigDecimal("30000.00"), result.remainingPrincipal());
        assertEquals(new BigDecimal("0.00"), result.paidPenalty());
        assertEquals(new BigDecimal("49.32"), result.remainingPenalty());
    }

    @Test
    void remainderAfterPrincipalPaysPenalty() {
        var result = allocate("80020.00");

        assertEquals(new BigDecimal("0.00"), result.remainingPrincipal());
        assertEquals(new BigDecimal("20.00"), result.paidPenalty());
        assertEquals(new BigDecimal("29.32"), result.remainingPenalty());
    }

    @Test
    void paymentCanClosePrincipalAndPenalty() {
        var result = allocate("80049.32");

        assertEquals(new BigDecimal("0.00"), result.remainingPrincipal());
        assertEquals(new BigDecimal("49.32"), result.paidPenalty());
        assertEquals(new BigDecimal("0.00"), result.remainingPenalty());
    }

    private ClaimPaymentAllocationCalculator.AllocationResult allocate(String paid) {
        return ClaimPaymentAllocationCalculator.allocate(
                new BigDecimal("80000.00"),
                new BigDecimal("49.32"),
                new BigDecimal(paid)
        );
    }
}

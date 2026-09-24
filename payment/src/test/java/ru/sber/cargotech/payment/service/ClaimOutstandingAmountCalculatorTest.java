package ru.sber.cargotech.payment.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.payment.repository.ClaimPaymentData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimOutstandingAmountCalculatorTest {

    @Test
    void includesPenaltyAfterPrincipalDebt() {
        ClaimPaymentData claim = claim("50000.00", "30000.00", "49.32");

        BigDecimal remaining = ClaimOutstandingAmountCalculator.calculate(
                claim,
                new BigDecimal("50000.00")
        );

        assertEquals(new BigDecimal("30049.32"), remaining);
    }

    @Test
    void subtractsPaymentsMadeAfterLastCalculationOnlyOnce() {
        ClaimPaymentData claim = claim("50000.00", "30000.00", "49.32");

        var remaining = ClaimOutstandingAmountCalculator.calculateBreakdown(
                claim,
                new BigDecimal("80020.00")
        );

        assertEquals(new BigDecimal("0.00"), remaining.remainingPrincipal());
        assertEquals(new BigDecimal("29.32"), remaining.remainingPenalty());
        assertEquals(new BigDecimal("29.32"), remaining.total());
    }

    @Test
    void reachesZeroWhenPrincipalAndPenaltyArePaid() {
        ClaimPaymentData claim = claim("50000.00", "30000.00", "49.32");

        BigDecimal remaining = ClaimOutstandingAmountCalculator.calculate(
                claim,
                new BigDecimal("80049.32")
        );

        assertEquals(new BigDecimal("0.00"), remaining);
    }

    private ClaimPaymentData claim(
            String calculatedPaid,
            String remainingPrincipal,
            String remainingPenalty
    ) {
        return new ClaimPaymentData(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CLM-TEST",
                "7701234567",
                "SHIPMENT-TEST",
                new BigDecimal("80000.00"),
                new BigDecimal(calculatedPaid),
                new BigDecimal(remainingPrincipal),
                new BigDecimal(remainingPenalty),
                "PENDING_LEGAL_REVIEW"
        );
    }
}

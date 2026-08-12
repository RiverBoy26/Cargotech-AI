package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.enums.PenaltyCapBase;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenaltyCapCalculatorTest {

    @Test
    void capsPenaltyByShipmentCostPercent() {
        BigDecimal result = PenaltyCapCalculator.apply(
            new BigDecimal("35000"),
            new BigDecimal("20"),
            PenaltyCapBase.SHIPMENT_COST,
            new BigDecimal("100000"),
            BigDecimal.ZERO
        );

        assertEquals(new BigDecimal("20000.00"), result);
    }

    @Test
    void capsByCurrentOutstandingDebtWhenConfigured() {
        BigDecimal result = PenaltyCapCalculator.apply(
            new BigDecimal("8000"),
            new BigDecimal("10"),
            PenaltyCapBase.OUTSTANDING_DEBT,
            new BigDecimal("100000"),
            new BigDecimal("40000")
        );

        assertEquals(new BigDecimal("6000.00"), result);
    }

    @Test
    void leavesPenaltyUntouchedWithoutCap() {
        BigDecimal result = PenaltyCapCalculator.apply(
            new BigDecimal("1234.56"),
            null,
            null,
            new BigDecimal("100000"),
            BigDecimal.ZERO
        );

        assertEquals(new BigDecimal("1234.56"), result);
    }
}

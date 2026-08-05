package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PenaltyScheduleCalculatorTest {

    private static final LocalDate OVERDUE_START = LocalDate.of(2026, 7, 31);
    private static final LocalDate CALCULATION_DATE = LocalDate.of(2026, 8, 5);

    @Test
    void partialPaymentReducesPenaltyBaseOnlyFromPaymentDate() {
        BigDecimal penalty = PenaltyScheduleCalculator.calculate(
                new BigDecimal("80000.00"),
                OVERDUE_START,
                CALCULATION_DATE,
                PenaltyType.CONTRACT_PENALTY,
                new BigDecimal("0.1"),
                List.of(new PenaltyScheduleCalculator.Allocation(
                        LocalDate.of(2026, 8, 3),
                        new BigDecimal("50000.00")
                ))
        );

        assertEquals(new BigDecimal("300.00"), penalty);
    }

    @Test
    void paymentBeforeOverdueReducesBaseForWholeOverduePeriod() {
        BigDecimal penalty = PenaltyScheduleCalculator.calculate(
                new BigDecimal("80000.00"),
                OVERDUE_START,
                CALCULATION_DATE,
                PenaltyType.CONTRACT_PENALTY,
                new BigDecimal("0.1"),
                List.of(new PenaltyScheduleCalculator.Allocation(
                        LocalDate.of(2026, 7, 30),
                        new BigDecimal("50000.00")
                ))
        );

        assertEquals(new BigDecimal("150.00"), penalty);
    }

    @Test
    void paymentOnCalculationDateDoesNotReduceAlreadyAccruedPenalty() {
        BigDecimal penalty = PenaltyScheduleCalculator.calculate(
                new BigDecimal("80000.00"),
                OVERDUE_START,
                CALCULATION_DATE,
                PenaltyType.CONTRACT_PENALTY,
                new BigDecimal("0.1"),
                List.of(new PenaltyScheduleCalculator.Allocation(
                        CALCULATION_DATE,
                        new BigDecimal("50000.00")
                ))
        );

        assertEquals(new BigDecimal("400.00"), penalty);
    }
}

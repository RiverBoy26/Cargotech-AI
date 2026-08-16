package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void article395IsSplitWhenKeyRateChangesInsideOverduePeriod() {
        BigDecimal penalty = PenaltyScheduleCalculator.calculate(
                new BigDecimal("36500.00"),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 11),
                PenaltyType.ARTICLE_395,
                new BigDecimal("10.0"),
                List.of(),
                List.of(
                        new PenaltyScheduleCalculator.RatePeriod(
                                LocalDate.of(2026, 8, 1),
                                LocalDate.of(2026, 8, 5),
                                new BigDecimal("10.0")
                        ),
                        new PenaltyScheduleCalculator.RatePeriod(
                                LocalDate.of(2026, 8, 6),
                                LocalDate.of(2026, 8, 11),
                                new BigDecimal("20.0")
                        )
                )
        );

        assertEquals(new BigDecimal("150.00"), penalty);
    }
    @Test
    void article395NeverUsesFallbackWhenRatePeriodsAreMissing() {
        assertThrows(IllegalStateException.class, () -> PenaltyScheduleCalculator.calculate(
                new BigDecimal("36500.00"),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 11),
                PenaltyType.ARTICLE_395,
                new BigDecimal("99.0"),
                List.of(),
                List.of()
        ));
    }

    @Test
    void article395Uses366DaysForLeapYearSegments() {
        BigDecimal penalty = PenaltyScheduleCalculator.calculate(
                new BigDecimal("36600.00"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 1, 11),
                PenaltyType.ARTICLE_395,
                null,
                List.of(),
                List.of(new PenaltyScheduleCalculator.RatePeriod(
                        LocalDate.of(2024, 1, 1),
                        LocalDate.of(2024, 1, 10),
                        new BigDecimal("10.0")
                ))
        );

        assertEquals(new BigDecimal("100.00"), penalty);
    }

    @Test
    void article395SplitsDenominatorAcrossCalendarYears() {
        BigDecimal penalty = PenaltyScheduleCalculator.calculate(
                new BigDecimal("36600.00"),
                LocalDate.of(2024, 12, 30),
                LocalDate.of(2025, 1, 2),
                PenaltyType.ARTICLE_395,
                null,
                List.of(),
                List.of(new PenaltyScheduleCalculator.RatePeriod(
                        LocalDate.of(2024, 12, 30),
                        LocalDate.of(2025, 1, 1),
                        new BigDecimal("10.0")
                ))
        );

        assertEquals(new BigDecimal("30.03"), penalty);
    }

}

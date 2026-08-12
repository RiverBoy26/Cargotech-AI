package ru.sber.cargotech.claim.service;

import ru.sber.cargotech.claim.enums.PenaltyType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.TreeMap;

final class PenaltyScheduleCalculator {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal DAYS_IN_YEAR = new BigDecimal("365");

    private PenaltyScheduleCalculator() {
    }

    static BigDecimal calculate(
            BigDecimal principalDebt,
            LocalDate overdueStartDate,
            LocalDate calculationDate,
            PenaltyType penaltyType,
            BigDecimal penaltyRate,
            List<Allocation> allocations
    ) {
        return calculate(
                principalDebt,
                overdueStartDate,
                calculationDate,
                penaltyType,
                penaltyRate,
                allocations,
                List.of()
        );
    }

    static BigDecimal calculate(
            BigDecimal principalDebt,
            LocalDate overdueStartDate,
            LocalDate calculationDate,
            PenaltyType penaltyType,
            BigDecimal penaltyRate,
            List<Allocation> allocations,
            List<RatePeriod> ratePeriods
    ) {
        if (principalDebt == null
                || principalDebt.signum() <= 0
                || overdueStartDate == null
                || calculationDate == null
                || !calculationDate.isAfter(overdueStartDate)
                || penaltyType == null
                || penaltyType == PenaltyType.NONE
                || penaltyRate == null
                || penaltyRate.signum() <= 0) {
            return money(BigDecimal.ZERO);
        }

        TreeMap<LocalDate, BigDecimal> paymentsByDate = new TreeMap<>();
        if (allocations != null) {
            allocations.stream()
                    .filter(allocation -> allocation != null
                            && allocation.paymentDate() != null
                            && allocation.amount() != null
                            && allocation.amount().signum() > 0)
                    .forEach(allocation -> paymentsByDate.merge(
                            allocation.paymentDate(),
                            allocation.amount(),
                            BigDecimal::add
                    ));
        }

        if (penaltyType == PenaltyType.ARTICLE_395 && ratePeriods != null && !ratePeriods.isEmpty()) {
            return calculateArticle395ByRatePeriods(
                    principalDebt,
                    overdueStartDate,
                    calculationDate,
                    penaltyRate,
                    paymentsByDate,
                    ratePeriods
            );
        }

        BigDecimal outstandingDebt = principalDebt;
        BigDecimal accruedPenalty = BigDecimal.ZERO;
        LocalDate periodStart = overdueStartDate;

        for (var payment : paymentsByDate.entrySet()) {
            LocalDate paymentDate = payment.getKey();
            if (paymentDate.isAfter(calculationDate)) {
                break;
            }

            if (paymentDate.isAfter(periodStart)) {
                long days = ChronoUnit.DAYS.between(periodStart, paymentDate);
                accruedPenalty = accruedPenalty.add(calculatePeriod(
                        outstandingDebt,
                        days,
                        penaltyType,
                        penaltyRate
                ));
                periodStart = paymentDate;
            }

            outstandingDebt = outstandingDebt
                    .subtract(payment.getValue())
                    .max(BigDecimal.ZERO);
        }

        if (calculationDate.isAfter(periodStart)) {
            long days = ChronoUnit.DAYS.between(periodStart, calculationDate);
            accruedPenalty = accruedPenalty.add(calculatePeriod(
                    outstandingDebt,
                    days,
                    penaltyType,
                    penaltyRate
            ));
        }

        return money(accruedPenalty);
    }

    private static BigDecimal calculateArticle395ByRatePeriods(
            BigDecimal principalDebt,
            LocalDate overdueStartDate,
            LocalDate calculationDate,
            BigDecimal fallbackRate,
            TreeMap<LocalDate, BigDecimal> paymentsByDate,
            List<RatePeriod> ratePeriods
    ) {
        BigDecimal outstandingDebt = principalDebt;
        for (var payment : paymentsByDate.headMap(overdueStartDate, true).values()) {
            outstandingDebt = outstandingDebt.subtract(payment).max(BigDecimal.ZERO);
        }

        SortedSet<LocalDate> boundaries = new TreeSet<>();
        boundaries.add(overdueStartDate);
        boundaries.add(calculationDate);
        paymentsByDate.keySet().stream()
                .filter(date -> date.isAfter(overdueStartDate) && date.isBefore(calculationDate))
                .forEach(boundaries::add);
        ratePeriods.forEach(period -> {
            if (period.from() != null && period.from().isAfter(overdueStartDate)
                    && period.from().isBefore(calculationDate)) {
                boundaries.add(period.from());
            }
            if (period.to() != null) {
                LocalDate after = period.to().plusDays(1);
                if (after.isAfter(overdueStartDate) && after.isBefore(calculationDate)) {
                    boundaries.add(after);
                }
            }
        });

        List<LocalDate> ordered = new java.util.ArrayList<>(boundaries);
        BigDecimal accrued = BigDecimal.ZERO;
        for (int index = 0; index + 1 < ordered.size(); index++) {
            LocalDate from = ordered.get(index);
            LocalDate to = ordered.get(index + 1);
            if (from.isAfter(overdueStartDate)) {
                outstandingDebt = outstandingDebt
                        .subtract(paymentsByDate.getOrDefault(from, BigDecimal.ZERO))
                        .max(BigDecimal.ZERO);
            }
            long days = ChronoUnit.DAYS.between(from, to);
            accrued = accrued.add(calculatePeriod(
                    outstandingDebt,
                    days,
                    PenaltyType.ARTICLE_395,
                    rateAt(from, ratePeriods, fallbackRate)
            ));
        }
        return money(accrued);
    }

    private static BigDecimal rateAt(
            LocalDate date,
            List<RatePeriod> periods,
            BigDecimal fallbackRate
    ) {
        return periods.stream()
                .filter(period -> period.from() != null && !date.isBefore(period.from()))
                .filter(period -> period.to() == null || !date.isAfter(period.to()))
                .map(RatePeriod::rate)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(fallbackRate);
    }

    private static BigDecimal calculatePeriod(
            BigDecimal debt,
            long days,
            PenaltyType penaltyType,
            BigDecimal penaltyRate
    ) {
        if (debt.signum() <= 0 || days <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal percent = penaltyRate.divide(
                ONE_HUNDRED,
                12,
                RoundingMode.HALF_UP
        );
        BigDecimal penalty = debt
                .multiply(percent)
                .multiply(BigDecimal.valueOf(days));

        if (penaltyType == PenaltyType.ARTICLE_395) {
            penalty = penalty.divide(
                    DAYS_IN_YEAR,
                    12,
                    RoundingMode.HALF_UP
            );
        }

        return penalty;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    record Allocation(LocalDate paymentDate, BigDecimal amount) {
    }

    record RatePeriod(LocalDate from, LocalDate to, BigDecimal rate) {
    }
}

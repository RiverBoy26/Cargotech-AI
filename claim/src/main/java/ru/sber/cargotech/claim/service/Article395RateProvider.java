package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.sber.cargotech.claim.entity.Article395Rate;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.Article395RateRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Article395RateProvider {
    private final Article395RateRepository repository;

    /**
     * Returns a complete set of Bank of Russia key-rate periods covering the
     * accrued interval [overdueStartDate, calculationDate).
     *
     * <p>Article 395 must never fall back to a guessed/default percentage. If
     * the database does not contain the rate applicable on the first overdue
     * day, calculation is blocked so that an incorrect legal amount cannot be
     * persisted or sent to the LLM.</p>
     */
    public List<PenaltyScheduleCalculator.RatePeriod> periods(
            LocalDate overdueStartDate,
            LocalDate calculationDate
    ) {
        if (overdueStartDate == null || calculationDate == null) {
            return List.of();
        }
        if (!calculationDate.isAfter(overdueStartDate)) {
            return List.of();
        }

        List<Article395Rate> rates = repository
                .findByEffectiveFromLessThanEqualOrderByEffectiveFromAsc(calculationDate);

        int firstApplicableIndex = -1;
        for (int index = 0; index < rates.size(); index++) {
            if (!rates.get(index).getEffectiveFrom().isAfter(overdueStartDate)) {
                firstApplicableIndex = index;
            } else {
                break;
            }
        }

        if (firstApplicableIndex < 0) {
            throw ClaimException.validation(
                    "Невозможно рассчитать проценты по ст. 395 ГК РФ: "
                            + "в справочнике отсутствует ключевая ставка Банка России на дату начала просрочки "
                            + overdueStartDate
            );
        }

        List<PenaltyScheduleCalculator.RatePeriod> periods = new ArrayList<>();
        LocalDate lastAccruedDate = calculationDate.minusDays(1);
        for (int index = firstApplicableIndex; index < rates.size(); index++) {
            Article395Rate current = rates.get(index);
            LocalDate from = current.getEffectiveFrom().isBefore(overdueStartDate)
                    ? overdueStartDate
                    : current.getEffectiveFrom();
            if (from.isAfter(lastAccruedDate)) {
                break;
            }

            LocalDate to = index + 1 < rates.size()
                    ? rates.get(index + 1).getEffectiveFrom().minusDays(1)
                    : lastAccruedDate;
            if (to.isAfter(lastAccruedDate)) {
                to = lastAccruedDate;
            }
            if (to.isBefore(from)) {
                continue;
            }

            periods.add(new PenaltyScheduleCalculator.RatePeriod(
                    from,
                    to,
                    current.getRate(),
                    current.getSource()
            ));
        }

        validateContinuousCoverage(overdueStartDate, lastAccruedDate, periods);
        return List.copyOf(periods);
    }

    private void validateContinuousCoverage(
            LocalDate expectedFrom,
            LocalDate expectedTo,
            List<PenaltyScheduleCalculator.RatePeriod> periods
    ) {
        LocalDate cursor = expectedFrom;
        for (PenaltyScheduleCalculator.RatePeriod period : periods) {
            if (!period.from().equals(cursor)
                    || period.to() == null
                    || period.rate() == null
                    || period.rate().signum() <= 0) {
                throw missingRate(expectedFrom, expectedTo);
            }
            cursor = period.to().plusDays(1);
        }
        if (!cursor.equals(expectedTo.plusDays(1))) {
            throw missingRate(expectedFrom, expectedTo);
        }
    }

    private ClaimException missingRate(LocalDate expectedFrom, LocalDate expectedTo) {
        return ClaimException.validation(
                "Невозможно рассчитать проценты по ст. 395 ГК РФ: "
                        + "справочник ключевой ставки Банка России не покрывает весь период "
                        + expectedFrom + " — " + expectedTo
        );
    }
}

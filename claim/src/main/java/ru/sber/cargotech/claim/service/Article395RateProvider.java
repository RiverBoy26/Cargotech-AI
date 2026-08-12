package ru.sber.cargotech.claim.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.sber.cargotech.claim.entity.Article395Rate;
import ru.sber.cargotech.claim.repository.Article395RateRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class Article395RateProvider {
    private final Article395RateRepository repository;

    public List<PenaltyScheduleCalculator.RatePeriod> periods(
            LocalDate overdueStartDate,
            LocalDate calculationDate
    ) {
        if (overdueStartDate == null || calculationDate == null) {
            return List.of();
        }
        List<Article395Rate> rates = repository
                .findByEffectiveFromLessThanEqualOrderByEffectiveFromAsc(calculationDate);
        List<PenaltyScheduleCalculator.RatePeriod> periods = new ArrayList<>();
        for (int index = 0; index < rates.size(); index++) {
            Article395Rate current = rates.get(index);
            LocalDate to = index + 1 < rates.size()
                    ? rates.get(index + 1).getEffectiveFrom().minusDays(1)
                    : calculationDate;
            if (!to.isBefore(overdueStartDate)) {
                periods.add(new PenaltyScheduleCalculator.RatePeriod(
                        current.getEffectiveFrom(),
                        to,
                        current.getRate()
                ));
            }
        }
        return List.copyOf(periods);
    }
}

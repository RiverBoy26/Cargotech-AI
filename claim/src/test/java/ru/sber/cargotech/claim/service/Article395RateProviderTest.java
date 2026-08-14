package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.entity.Article395Rate;
import ru.sber.cargotech.claim.exception.ClaimException;
import ru.sber.cargotech.claim.repository.Article395RateRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Article395RateProviderTest {

    private final Article395RateRepository repository = mock(Article395RateRepository.class);
    private final Article395RateProvider provider = new Article395RateProvider(repository);

    @Test
    void returnsExactPeriodsAcrossKeyRateChange() {
        LocalDate calculationDate = LocalDate.of(2026, 8, 1);
        when(repository.findByEffectiveFromLessThanEqualOrderByEffectiveFromAsc(calculationDate))
            .thenReturn(List.of(
                rate(LocalDate.of(2026, 6, 22), "14.25"),
                rate(LocalDate.of(2026, 7, 27), "14.00")
            ));

        var periods = provider.periods(LocalDate.of(2026, 7, 20), calculationDate);

        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).from()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(periods.get(0).to()).isEqualTo(LocalDate.of(2026, 7, 26));
        assertThat(periods.get(0).rate()).isEqualByComparingTo("14.25");
        assertThat(periods.get(1).from()).isEqualTo(LocalDate.of(2026, 7, 27));
        assertThat(periods.get(1).to()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(periods.get(1).rate()).isEqualByComparingTo("14.00");
    }

    @Test
    void blocksCalculationWhenRateAtOverdueStartIsMissing() {
        LocalDate calculationDate = LocalDate.of(2026, 8, 1);
        when(repository.findByEffectiveFromLessThanEqualOrderByEffectiveFromAsc(calculationDate))
            .thenReturn(List.of(rate(LocalDate.of(2026, 7, 27), "14.00")));

        assertThatThrownBy(() -> provider.periods(LocalDate.of(2026, 7, 20), calculationDate))
            .isInstanceOf(ClaimException.class)
            .hasMessageContaining("отсутствует ключевая ставка Банка России")
            .hasMessageContaining("2026-07-20");
    }

    private Article395Rate rate(LocalDate effectiveFrom, String value) {
        Article395Rate rate = new Article395Rate();
        rate.setEffectiveFrom(effectiveFrom);
        rate.setRate(new BigDecimal(value));
        rate.setSource("https://www.cbr.ru/hd_base/KeyRate/");
        return rate;
    }
}

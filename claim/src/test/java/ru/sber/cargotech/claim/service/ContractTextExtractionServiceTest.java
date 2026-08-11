package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.claim.dto.ContractExtractionCandidateRequest;
import ru.sber.cargotech.claim.enums.ContractExtractionField;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTextExtractionServiceTest {

    private final ContractTextExtractionService service = new ContractTextExtractionService();

    @Test
    void extractsOnlyValuesPresentInContractAndKeepsExactSourceAndPage() {
        String text = """
            2.4. Оплата производится в течение 30 календарных дней с даты подписания акта.
            [[PAGE:2]]
            6.3. За просрочку оплаты начисляется пеня 0,1% от суммы долга за каждый день.
            8.2. Ответ на претензию направляется в течение 10 рабочих дней.
            9.1. Споры рассматривает Арбитражный суд Приморского края.
            """;

        List<ContractExtractionCandidateRequest> values = service.extract(text).candidates();

        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.PAYMENT_DAYS);
            assertThat(value.value()).isEqualTo("30");
            assertThat(value.source()).isEqualTo("2.4. Оплата производится в течение 30 календарных дней с даты подписания акта.");
            assertThat(value.sourcePage()).isEqualTo(1);
            assertThat(value.clauseNumber()).isEqualTo("2.4");
        });
        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.PENALTY_RATE);
            assertThat(value.value()).isEqualTo("0.1");
            assertThat(value.sourcePage()).isEqualTo(2);
        });
        assertThat(values).anySatisfy(value ->
            assertThat(value.field()).isEqualTo(ContractExtractionField.JURISDICTION)
        );
    }

    @Test
    void doesNotInventMissingTerms() {
        List<ContractExtractionCandidateRequest> values = service.extract(
            "1.1. Перевозчик обязуется доставить груз по заявке заказчика."
        ).candidates();

        assertThat(values).isEmpty();
    }
}

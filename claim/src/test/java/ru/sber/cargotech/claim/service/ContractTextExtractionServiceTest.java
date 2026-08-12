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

        assertThat(values)
            .filteredOn(value -> value.field() != ContractExtractionField.EXACT_CLAUSE)
            .allSatisfy(value -> {
                assertThat(value.value()).isNull();
                assertThat(value.source()).isNull();
                assertThat(value.confidence()).isNull();
                assertThat(value.clauseNumber()).isNull();
            });
        assertThat(values).noneMatch(value -> value.field() == ContractExtractionField.EXACT_CLAUSE);
    }

    @Test
    void extractsContractNumberAndTextualSignedDateFromHeader() {
        List<ContractExtractionCandidateRequest> values = service.extract(
            "ДОГОВОР № ДЭ-2026/001 от 12 августа 2026 г."
        ).candidates();

        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.CONTRACT_NUMBER);
            assertThat(value.value()).isEqualTo("ДЭ-2026/001");
            assertThat(value.source()).contains("ДОГОВОР");
        });
        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.SIGNED_AT);
            assertThat(value.value()).isEqualTo("2026-08-12");
        });
    }

    @Test
    void doesNotInventInvalidContractDateOrNumber() {
        List<ContractExtractionCandidateRequest> values = service.extract(
            "Проект соглашения от 32.13.2026 без номера договора."
        ).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.CONTRACT_NUMBER
                || value.field() == ContractExtractionField.SIGNED_AT)
            .allSatisfy(value -> assertThat(value.value()).isNull());
    }


    @Test
    void extractsParenthesizedPaymentAndClaimDaysWithoutPickingUnrelatedThirtyDayClause() {
        String text = """
            8.2. Клиент производит оплату оказанных услуг в течение 45 (сорока пяти) календарных дней с даты получения полного комплекта документов.
            10.2. Сторона, получившая претензию, рассматривает ее и направляет мотивированный письменный ответ в течение 30 (тридцать) календарных дней.
            15.3. Любая Сторона вправе отказаться от дальнейшего исполнения рамочного Договора, уведомив другую Сторону за 30 календарных дней, при условии завершения взаиморасчетов.
            """;

        List<ContractExtractionCandidateRequest> values = service.extract(text).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.PAYMENT_DAYS)
            .singleElement()
            .satisfies(value -> {
                assertThat(value.value()).isEqualTo("45");
                assertThat(value.clauseNumber()).isEqualTo("8.2");
            });
        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.CLAIM_RESPONSE_DAYS)
            .singleElement()
            .satisfies(value -> {
                assertThat(value.value()).isEqualTo("30");
                assertThat(value.clauseNumber()).isEqualTo("10.2");
            });
        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.PAYMENT_START_EVENT)
            .singleElement()
            .satisfies(value -> {
                assertThat(value.value()).isEqualTo("DOCUMENT_PACKAGE_RECEIVED");
                assertThat(value.source()).contains("полного комплекта документов");
            });
        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.EXACT_CLAUSE)
            .noneMatch(value -> value.value().startsWith("15.3."));
    }

    @Test
    void genericPenaltyAndUnrelatedPercentageDoNotBecomePaymentPenalty() {
        String text = """
            9.2. Штрафы и неустойки не освобождают Стороны от исполнения основного обязательства. Уплата санкций производится на основании письменного требования.
            9.5. За неподачу транспортного средства виновная Сторона уплачивает штраф в размере 15 % стоимости перевозки.
            """;

        List<ContractExtractionCandidateRequest> values = service.extract(text).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.PENALTY_TYPE
                || value.field() == ContractExtractionField.PENALTY_RATE)
            .allSatisfy(value -> assertThat(value.value()).isNull());
        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.EXACT_CLAUSE)
            .noneMatch(value -> value.clauseType() == ru.sber.cargotech.claim.enums.ClauseType.PENALTY);
    }

    @Test
    void extractsOnlyPenaltyExplicitlyConnectedWithPaymentDelay() {
        String text = """
            9.2. Штрафы и неустойки не освобождают Стороны от исполнения основного обязательства.
            9.4. При нарушении Клиентом срока оплаты Экспедитор вправе потребовать уплаты неустойки 0,05 % от суммы просроченного платежа за каждый календарный день просрочки.
            """;

        List<ContractExtractionCandidateRequest> values = service.extract(text).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.PENALTY_TYPE)
            .singleElement()
            .satisfies(value -> {
                assertThat(value.value()).isEqualTo("CONTRACT_PENALTY");
                assertThat(value.clauseNumber()).isEqualTo("9.4");
            });
        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.PENALTY_RATE)
            .singleElement()
            .satisfies(value -> {
                assertThat(value.value()).isEqualTo("0.05");
                assertThat(value.clauseNumber()).isEqualTo("9.4");
            });
    }

    @Test
    void claimAttachmentsAreNotMisclassifiedAsClaimProcedure() {
        String text = """
            10.2. Сторона, получившая претензию, рассматривает ее и направляет мотивированный письменный ответ в течение 30 (тридцать) календарных дней.
            12.5. К претензии по повреждению или недостаче груза прилагаются транспортная накладная, коммерческий акт или акт расхождений, расчет ущерба и документы о стоимости груза.
            """;

        List<ContractExtractionCandidateRequest> values = service.extract(text).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.EXACT_CLAUSE)
            .anyMatch(value -> value.value().startsWith("10.2."))
            .noneMatch(value -> value.value().startsWith("12.5."));
    }

    @Test
    void extractsCleanJurisdictionInsteadOfWholeClause() {
        List<ContractExtractionCandidateRequest> values = service.extract(
            "10.3. При недостижении соглашения спор подлежит рассмотрению в Арбитражном суде Алтайского края."
        ).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.JURISDICTION)
            .singleElement()
            .satisfies(value -> assertThat(value.value()).isEqualTo("Арбитражный суд Алтайского края"));
    }

    @Test
    void doesNotInventDocxPageNumberWhenExtractorCannotMapParagraphsToPages() {
        List<ContractExtractionCandidateRequest> values = service.extract(
            "8.2. Клиент производит оплату услуг в течение 45 календарных дней с даты подписания акта.",
            "APACHE_POI"
        ).candidates();

        assertThat(values)
            .filteredOn(value -> value.field() == ContractExtractionField.PAYMENT_DAYS)
            .singleElement()
            .satisfies(value -> assertThat(value.sourcePage()).isNull());
    }

    @Test
    void extractsBankingDayUnitRegistryAnchorAndClaimResponseUnit() {
        String text = """
            8.2. Заказчик производит оплату оказанных услуг в течение 5 (пяти) банковских дней после включения рейса в согласованный Сторонами реестр выполненных перевозок.
            10.2. Сторона, получившая претензию, рассматривает ее и направляет мотивированный письменный ответ в течение 10 (десять) календарных дней.
            """;

        List<ContractExtractionCandidateRequest> values = service.extract(text).candidates();

        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.PAYMENT_DAY_TYPE);
            assertThat(value.value()).isEqualTo("BANKING_DAYS");
            assertThat(value.clauseNumber()).isEqualTo("8.2");
        });
        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.PAYMENT_START_EVENT);
            assertThat(value.value()).isEqualTo("REGISTRY_INCLUDED");
            assertThat(value.clauseNumber()).isEqualTo("8.2");
        });
        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.CLAIM_RESPONSE_DAY_TYPE);
            assertThat(value.value()).isEqualTo("CALENDAR_DAYS");
            assertThat(value.clauseNumber()).isEqualTo("10.2");
        });
    }

    @Test
    void extractsFullDocumentPackageAsPaymentAnchor() {
        List<ContractExtractionCandidateRequest> values = service.extract(
            "7.6. Клиент осуществляет оплату в течение 60 календарных дней с момента предоставления полного пакета документов."
        ).candidates();

        assertThat(values).anySatisfy(value -> {
            assertThat(value.field()).isEqualTo(ContractExtractionField.PAYMENT_START_EVENT);
            assertThat(value.value()).isEqualTo("DOCUMENT_PACKAGE_RECEIVED");
        });
    }

}

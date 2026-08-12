package ru.sber.cargotech.claim.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContractRagChunkerTest {

    private final ContractRagChunker chunker = new ContractRagChunker();

    @Test
    void keepsArticleTitleClauseNumberAndPaymentClassification() {
        List<ContractRagChunker.ContractSourceChunk> chunks = chunker.chunk("""
            Статья 8
            Стоимость услуг и порядок расчётов
            8.1. Стоимость услуг согласуется в заявке.
            8.2. Оплата производится в течение 30 календарных дней после подписания акта.
            8.3. Иные условия определяются соглашением сторон.
            """);

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.sectionTitle()).isEqualTo("Статья 8. Стоимость услуг и порядок расчётов");
            assertThat(chunk.sectionPath()).isEqualTo(chunk.sectionTitle());
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.clauseNumber()).isEqualTo("8.2");
            assertThat(chunk.chunkType()).isEqualTo("PAYMENT_TERM");
            assertThat(chunk.claimType()).isEqualTo("PAYMENT_DELAY");
        });
    }

    @Test
    void longClauseIsSplitWithoutLosingWordsAndKeepsClauseNumber() {
        String body = ("обязательство исполняется надлежащим образом в согласованный срок. ").repeat(80).trim();
        List<ContractRagChunker.ContractSourceChunk> chunks = chunker.chunk("10.4. " + body);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.clauseNumber()).isEqualTo("10.4");
            assertThat(chunk.text().length()).isLessThanOrEqualTo(ContractRagChunker.MAX_CHUNK_LENGTH);
        });
        String restored = chunks.stream().map(ContractRagChunker.ContractSourceChunk::text)
            .reduce("", (left, right) -> left + " " + right).replaceAll("\\s+", " ").trim();
        assertThat(restored).isEqualTo(("10.4. " + body).replaceAll("\\s+", " ").trim());
    }

    @Test
    void unnumberedSectionNeverReceivesFakeClauseNumber() {
        List<ContractRagChunker.ContractSourceChunk> chunks = chunker.chunk(
            "Стороны обязуются добросовестно исполнять условия договора без дополнительных оговорок."
        );

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.clauseNumber()).isNull();
            assertThat(chunk.chunkType()).isEqualTo("CONTRACT_GENERAL");
            assertThat(chunk.claimType()).isEqualTo("ALL");
        });
    }

    @Test
    void classifiesPaymentPenaltyConservatively() {
        assertThat(single("4.2. За просрочку оплаты Заказчик уплачивает пеню 0,1 процента за каждый день.").chunkType())
            .isEqualTo("CONTRACT_PENALTY");
    }

    @Test
    void classifiesPretrialOrderForAllClaimTypes() {
        ContractRagChunker.ContractSourceChunk chunk = single(
            "9.1. Претензия рассматривается, мотивированный ответ направляется в срок 10 рабочих дней."
        );
        assertThat(chunk.chunkType()).isEqualTo("PRETRIAL_ORDER");
        assertThat(chunk.claimType()).isEqualTo("ALL");
    }

    @Test
    void classifiesVehicleSupplyDuty() {
        assertThat(single("3.1. Перевозчик обязан подать транспортное средство к месту погрузки.").chunkType())
            .isEqualTo("VEHICLE_SUPPLY_DUTY");
    }

    @Test
    void classifiesLoadingFailurePenalty() {
        assertThat(single("6.4. За неподачу транспортного средства к погрузке уплачивается штраф.").chunkType())
            .isEqualTo("LOADING_FAILURE_PENALTY");
    }

    @Test
    void documentDeliveryPenaltyIsNotMisclassifiedAsLoadingFailure() {
        assertThat(single("6.5. За непредоставление закрывающих документов уплачивается штраф.").chunkType())
            .isEqualTo("CONTRACT_GENERAL");
    }

    @Test
    void skipsRequisitesAndMasksPiiOutsideThatSection() {
        List<ContractRagChunker.ContractSourceChunk> chunks = chunker.chunk("""
            1.1. Для уведомлений используется адрес legal@example.ru и телефон +7 999 123-45-67.
            Раздел 12. Реквизиты и подписи сторон
            ООО Пример, р/с 40702810900000000001, bank@example.ru, +7 900 000-00-00
            """);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.text()).contains("[EMAIL УДАЛЕН]", "[ТЕЛЕФОН УДАЛЕН]");
            assertThat(chunk.text()).doesNotContain("legal@example.ru", "40702810900000000001", "bank@example.ru");
        });
    }

    @Test
    void mentionOfChangedRequisitesInsideClauseDoesNotTruncateContract() {
        List<ContractRagChunker.ContractSourceChunk> chunks = chunker.chunk("""
            11.1. Сторона обязана уведомить контрагента об изменении своих реквизитов в течение пяти дней.
            12.1. Ответ на претензию направляется в течение 10 рабочих дней.
            """);

        assertThat(chunks)
            .extracting(ContractRagChunker.ContractSourceChunk::clauseNumber)
            .containsExactly("11.1", "12.1");
    }

    @Test
    void carriesPdfPageMarkerIntoMetadata() {
        List<ContractRagChunker.ContractSourceChunk> chunks = chunker.chunk("""
            1.1. Общие условия договора применяются сторонами.
            [[PAGE:3]]
            2.1. Оплата производится в течение 5 дней после акта.
            """);
        assertThat(chunks)
            .filteredOn(chunk -> "2.1".equals(chunk.clauseNumber()))
            .singleElement()
            .satisfies(chunk -> assertThat(chunk.sourcePage()).isEqualTo(3));
    }

    private ContractRagChunker.ContractSourceChunk single(String text) {
        return chunker.chunk(text).getFirst();
    }
}

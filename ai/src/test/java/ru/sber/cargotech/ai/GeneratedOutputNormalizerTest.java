package ru.sber.cargotech.ai;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;
import ru.sber.cargotech.ai.guardrail.GeneratedOutputNormalizer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedOutputNormalizerTest {

    @Test
    void normalizesMixedAlphabetIdentifierInClaimAndAttachment() {
        GenerateClaimRequest request = paymentRequest();
        GenerateClaimResponse response = response(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "К претензии приложена TТН-157.",
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.TTN,
                        "TТН-157",
                        true
                ))
        );

        GenerateClaimResponse normalized = GeneratedOutputNormalizer.normalizeClaim(request, response);

        assertThat(normalized.claimText()).contains("ТТН-157").doesNotContain("TТН-157");
        assertThat(normalized.attachments())
                .filteredOn(item -> item.documentType() == GenerateClaimResponse.DocumentType.TTN)
                .singleElement()
                .extracting(GenerateClaimResponse.Attachment::documentName)
                .asString()
                .contains("ТТН-157");
    }

    @Test
    void completesPaymentAttachmentPackageAndCorrectsPenaltyPeriod() {
        GenerateClaimRequest request = paymentRequest();
        GenerateClaimResponse response = response(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                "Неустойка рассчитана за период с 16.07.2026 г. по 20.07.2026 г.",
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.ACT,
                        "Акт ACT-157 от 01.07.2026",
                        false
                ))
        );

        GenerateClaimResponse normalized = GeneratedOutputNormalizer.normalizeClaim(request, response);

        assertThat(normalized.claimText())
                .contains("за период с 17.07.2026 г. по 20.07.2026 г.")
                .doesNotContain("за период с 16.07.2026");
        assertThat(normalized.attachments())
                .extracting(GenerateClaimResponse.Attachment::documentType)
                .contains(
                        GenerateClaimResponse.DocumentType.CONTRACT,
                        GenerateClaimResponse.DocumentType.ACT,
                        GenerateClaimResponse.DocumentType.TTN,
                        GenerateClaimResponse.DocumentType.INVOICE,
                        GenerateClaimResponse.DocumentType.CALCULATION
                );
        assertThat(normalized.attachments())
                .filteredOn(item -> item.documentType() == GenerateClaimResponse.DocumentType.ACT)
                .singleElement()
                .extracting(GenerateClaimResponse.Attachment::required)
                .isEqualTo(true);
    }

    @Test
    void normalizesLoadingFailureTermDispatcherAndRequiredAttachments() {
        GenerateClaimRequest request = loadingRequest();
        GenerateClaimResponse response = response(
                GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                "Факт непредставления транспортного средства подтверждён диспетчером ООО Перевозчик.",
                List.of()
        );

        GenerateClaimResponse normalized = GeneratedOutputNormalizer.normalizeClaim(request, response);

        assertThat(normalized.claimText())
                .contains("непредоставления транспортного средства")
                .contains("подтверждён диспетчером")
                .doesNotContain("диспетчером ООО Перевозчик")
                .doesNotContain("непредставлен");
        assertThat(normalized.attachments())
                .extracting(GenerateClaimResponse.Attachment::documentType)
                .contains(
                        GenerateClaimResponse.DocumentType.CONTRACT,
                        GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                        GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT,
                        GenerateClaimResponse.DocumentType.CALCULATION
                );
    }

    @Test
    void normalizesLoadingFailureDocumentTitleAndBindsActNumberToDate() {
        GenerateClaimRequest request = loadingRequest();
        GenerateDocumentResponse response = new GenerateDocumentResponse(
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT,
                "Акт о непредставлении транспортного средства",
                "По заявке ORD-LF-200 погрузка была назначена 12.06.2026. "
                        + "Транспортное средство представлено не было.",
                "Непредставление транспортного средства подтверждено диспетчером перевозчика.",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true
        );

        GenerateDocumentResponse normalized = GeneratedOutputNormalizer.normalizeDocument(
                request,
                response,
                GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT
        );

        assertThat(normalized.documentTitle())
                .isEqualTo("Акт о непредоставлении транспортного средства");
        assertThat(normalized.documentText())
                .startsWith("Акт №ACT-LF-200 о непредоставлении транспортного средства")
                .contains("Дата составления акта: 12.06.2026.")
                .contains("предоставлено не было")
                .doesNotContain("непредставлен");
        assertThat(normalized.summaryForLawyer())
                .contains("подтверждено диспетчером")
                .doesNotContain("диспетчером перевозчика");
        assertThat(normalized.attachments())
                .extracting(GenerateClaimResponse.Attachment::documentType)
                .contains(GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                        GenerateClaimResponse.DocumentType.CONTRACT);
    }

    @Test
    void appendsOnlyGroundedDeclaredCitationsMissingFromDocumentText() {
        GenerateClaimRequest request = loadingRequest();
        GenerateDocumentResponse response = new GenerateDocumentResponse(
                GenerateClaimResponse.DocumentType.NOTIFICATION,
                "Ошибочный заголовок",
                "Транспортное средство не было предоставлено к погрузке.",
                "Подготовлено уведомление.",
                List.of(
                        new GenerateClaimResponse.UsedContractClause("5.1", "chunk-contract-5-1", "обязанность"),
                        new GenerateClaimResponse.UsedContractClause("9.9", "unknown-chunk", "галлюцинация")
                ),
                List.of(new GenerateClaimResponse.UsedLawArticle(
                        "chunk-law-330",
                        "ГК РФ",
                        "330",
                        "неустойка"
                )),
                List.of(),
                List.of(),
                true
        );

        GenerateDocumentResponse normalized = GeneratedOutputNormalizer.normalizeDocument(
                request,
                response,
                GenerateClaimResponse.DocumentType.NOTIFICATION
        );

        assertThat(normalized.documentTitle())
                .isEqualTo("Уведомление о составлении акта о непредоставлении транспортного средства");
        assertThat(normalized.documentText())
                .contains("п. 5.1 договора")
                .contains("ст. 330 ГК РФ")
                .doesNotContain("п. 9.9 договора");
        assertThat(normalized.usedContractClauses()).hasSize(2);
    }

    private GenerateClaimResponse response(
            GenerateClaimRequest.ClaimType type,
            String text,
            List<GenerateClaimResponse.Attachment> attachments
    ) {
        return new GenerateClaimResponse(
                type,
                text,
                "Краткое описание",
                List.of(),
                List.of(),
                new GenerateClaimResponse.BackendCalculationUsed(
                        type == GenerateClaimRequest.ClaimType.PAYMENT_DELAY ? new BigDecimal("100000") : BigDecimal.ZERO,
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        type == GenerateClaimRequest.ClaimType.PAYMENT_DELAY ? new BigDecimal("200") : new BigDecimal("15000"),
                        type == GenerateClaimRequest.ClaimType.PAYMENT_DELAY ? new BigDecimal("100200") : new BigDecimal("15000"),
                        type == GenerateClaimRequest.ClaimType.PAYMENT_DELAY ? 4 : 0,
                        "RUB"
                ),
                attachments,
                List.of(),
                true
        );
    }

    private GenerateClaimRequest paymentRequest() {
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim-payment-normalizer",
                        GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        new GenerateClaimRequest.Party("ООО Экспедитор", "7700000000", "Москва"),
                        new GenerateClaimRequest.Party("ООО Клиент", "7800000000", "Санкт-Петербург"),
                        new GenerateClaimRequest.ContractFacts("AGRO-15/2026", "01.02.2026"),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-157", "Москва-Тула", "ACT-157", "01.07.2026", "ТТН-157", "INV-157"
                        ),
                        new GenerateClaimRequest.PaymentFacts(
                                "16.07.2026", GenerateClaimRequest.PaymentStatus.UNPAID, true
                        ),
                        "20.07.2026"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        new BigDecimal("100000"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        "0,05% в день",
                        4,
                        new BigDecimal("200"),
                        new BigDecimal("100200"),
                        "RUB",
                        "100000 × 0,05% × 4 = 200"
                ),
                List.of(), List.of(), null, List.of()
        );
    }

    private GenerateClaimRequest loadingRequest() {
        GenerateClaimRequest.ClaimType type = GenerateClaimRequest.ClaimType.LOADING_FAILURE;
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim-loading-normalizer",
                        type,
                        new GenerateClaimRequest.Party("ООО Клиент", "7700000000", "Москва"),
                        new GenerateClaimRequest.Party("ООО Перевозчик", "7800000000", "Санкт-Петербург"),
                        new GenerateClaimRequest.ContractFacts("LF-77/2026", "05.02.2026"),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-LF-200", "Москва-Казань", "ACT-LF-200", "12.06.2026", null, null,
                                "12.06.2026", "Москва, склад №4", "09:00-12:00", "тент 20 т",
                                "ООО Перевозчик", true
                        ),
                        null,
                        "13.06.2026"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        BigDecimal.ZERO,
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        "фиксированный штраф",
                        0,
                        new BigDecimal("15000"),
                        new BigDecimal("15000"),
                        "RUB",
                        "15000"
                ),
                List.of(new GenerateClaimRequest.ContractContextChunk(
                        "chunk-contract-5-1", "5.1", "Подача ТС", "Перевозчик обязан предоставить ТС"
                )),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "chunk-law-330", "ГК РФ", "330", "неустойка", "Понятие неустойки",
                        "ст. 330 ГК РФ", "2026-07-30", type.name()
                )),
                null,
                List.of()
        );
    }
}

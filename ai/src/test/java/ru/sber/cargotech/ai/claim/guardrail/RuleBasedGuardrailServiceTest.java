package ru.sber.cargotech.ai.claim.guardrail;

import org.junit.jupiter.api.Test;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedGuardrailServiceTest {

    private final RuleBasedGuardrailService service = new RuleBasedGuardrailService(
            new ClaimFactConsistencyValidator()
    );

    @Test
    void passesFactuallyConsistentPaymentDelayClaim() {
        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(validText()));
        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void acceptsIsoInputDatesWhenClaimUsesRussianNumericDates() {
        GenerateClaimRequest base = paymentRequest(true);
        GenerateClaimRequest request = new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        base.caseFacts().claimId(),
                        base.caseFacts().claimType(),
                        base.caseFacts().creditor(),
                        base.caseFacts().debtor(),
                        new GenerateClaimRequest.ContractFacts("45/2026", "2026-01-10"),
                        base.caseFacts().shipment(),
                        new GenerateClaimRequest.PaymentFacts(
                                "2026-05-31",
                                base.caseFacts().payment().paymentStatus(),
                                base.caseFacts().payment().paymentConfirmedByAccountant()
                        ),
                        base.caseFacts().claimDate()
                ),
                base.backendCalculation(),
                base.contractContext(),
                base.legalContext(),
                base.templateContext(),
                base.similarExamples()
        );

        GuardrailResult result = service.check(request, validPaymentResponse(validText()));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void passesWhenTtnAndInvoiceArePresentOnlyInAttachments() {
        String text = """
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Клиент, ИНН 7700000000.
                Претензия по договору №45/2026 от 10.01.2026.
                Перевозка по маршруту Санкт-Петербург — Москва.
                Услуги подтверждены актом №157 от 01.05.2026.
                Срок оплаты истёк 31.05.2026. Основной долг составляет 240 000 руб.,
                неустойка — 2 400 руб., итого к оплате — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ.
                """;

        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksAttachmentWithWrongTtnNumber() {
        GenerateClaimResponse response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                validText(),
                "Просрочка оплаты по договору",
                List.of(new GenerateClaimResponse.UsedContractClause("4.2", "contract-payment", "срок оплаты")),
                List.of(new GenerateClaimResponse.UsedLawArticle("law-309", "ГК РФ", "309", "надлежащее исполнение")),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        10,
                        "RUB"
                ),
                List.of(
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.ACT, "Акт 157", true),
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.TTN, "ТТН-999", true),
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.INVOICE, "INV-157", true)
                ),
                List.of(),
                true
        );

        GuardrailResult result = service.check(paymentRequest(true), response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("shipment.ttn_number"));
    }

    @Test
    void blocksUnknownInnInsideClaimText() {
        String text = validText() + " Дополнительный получатель: ИНН 7812345678.";
        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("unknown INN"));
    }

    @Test
    void blocksAmountNotProducedByBackend() {
        String text = validText() + " Дополнительно просим оплатить 999 999 руб.";
        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("amount not present"));
    }

    @Test
    void allowsPartiallyPaidDebtWhenAccountantConfirmedIt() {
        GenerateClaimRequest request = paymentRequest(true);
        request = new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        request.caseFacts().claimId(),
                        request.caseFacts().claimType(),
                        request.caseFacts().creditor(),
                        request.caseFacts().debtor(),
                        request.caseFacts().contract(),
                        request.caseFacts().shipment(),
                        new GenerateClaimRequest.PaymentFacts(
                                "31.05.2026",
                                GenerateClaimRequest.PaymentStatus.PARTIALLY_PAID,
                                true
                        ),
                        request.caseFacts().claimDate()
                ),
                request.backendCalculation(),
                request.contractContext(),
                request.legalContext(),
                request.templateContext(),
                request.similarExamples()
        );

        GuardrailResult result = service.check(request, validPaymentResponse(validText()));
        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
    }

    @Test
    void acceptsReorderedLoadingAddressAndVerbalTimeWindow() {
        GuardrailResult result = service.check(
                detailedLoadingRequest(),
                detailedLoadingResponse(detailedLoadingText())
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksDifferentWarehouseNumber() {
        String text = detailedLoadingText().replace("складе №4", "складе №5");
        GuardrailResult result = service.check(detailedLoadingRequest(), detailedLoadingResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("shipment.loading_address"));
    }

    @Test
    void blocksDifferentLoadingTimeWindow() {
        String text = detailedLoadingText().replace("с 09:00 до 12:00", "с 10:00 до 13:00");
        GuardrailResult result = service.check(detailedLoadingRequest(), detailedLoadingResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("shipment.loading_time_window"));
    }

    @Test
    void blocksLoadingFailureConfirmationSubstitution() {
        String text = detailedLoadingText().replace(
                "Факт непредоставления транспортного средства подтверждён",
                "Факт неподтверждения подачи транспортного средства установлен"
        );

        GuardrailResult result = service.check(detailedLoadingRequest(), detailedLoadingResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("absence of confirmation"));
    }

    @Test
    void blocksInventedVehicleIdentityAndIncidentCause() {
        String text = detailedLoadingText()
                .replace("требовался тент 20 т", "требовалось транспортное средство марки «тент» 20 т")
                + " Водитель опоздал из-за поломки.";

        GuardrailResult result = service.check(detailedLoadingRequest(), detailedLoadingResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("vehicle identity detail"));
        assertThat(result.errors()).anyMatch(error -> error.contains("cause or incident circumstance"));
    }

    @Test
    void blocksContractPenaltyReclassifiedAsCompensation() {
        String text = detailedLoadingText().replace(
                "просим оплатить штраф 15 000 руб.",
                "просим выплатить компенсацию 15 000 руб."
        );

        GuardrailResult result = service.check(detailedLoadingRequest(), detailedLoadingResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("changes CONTRACT_PENALTY"));
    }

    @Test
    void acceptsInflectedNonProvisionPhraseWithoutNominativeForm() {
        GenerateClaimResponse base = detailedLoadingResponse(detailedLoadingText());
        GenerateClaimResponse response = new GenerateClaimResponse(
                base.claimType(),
                base.claimText(),
                "Претензия составлена по факту непредоставления транспортного средства",
                base.usedContractClauses(),
                base.usedLawArticles(),
                base.backendCalculationUsed(),
                base.attachments(),
                base.warnings(),
                base.manualReviewRequired()
        );

        GuardrailResult result = service.check(detailedLoadingRequest(), response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksOptionalLoadingFailureActWhenActFactsExist() {
        GenerateClaimResponse base = detailedLoadingResponse(detailedLoadingText());
        GenerateClaimResponse response = new GenerateClaimResponse(
                base.claimType(),
                base.claimText(),
                base.summaryForLawyer(),
                base.usedContractClauses(),
                base.usedLawArticles(),
                base.backendCalculationUsed(),
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT,
                        "Акт о срыве погрузки № ACT-LF-200 от 12.06.2026",
                        false
                )),
                base.warnings(),
                base.manualReviewRequired()
        );

        GuardrailResult result = service.check(detailedLoadingRequest(), response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("required LOADING_FAILURE_ACT"));
    }

    @Test
    void blocksUnconfirmedLoadingFailure() {
        GenerateClaimRequest request = loadingRequest(false);
        GenerateClaimResponse response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                "От ООО Экспедитор ИНН 7800000000 к ООО Перевозчик ИНН 7700000000. "
                        + "Договор LF-1 от 01.06.2026. Заявка ORD-1, погрузка 10.06.2026, "
                        + "Москва, маршрут Москва - Тверь. Штраф 20 000 руб., итог 20 000 руб.",
                "Срыв подачи",
                List.of(new GenerateClaimResponse.UsedContractClause("5.1", "lf-duty", "обязанность подачи")),
                List.of(new GenerateClaimResponse.UsedLawArticle("law-lf", "УАТ", "34", "ответственность")),
                new GenerateClaimResponse.BackendCalculationUsed(
                        BigDecimal.ZERO,
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("20000"),
                        new BigDecimal("20000"),
                        0,
                        "RUB"
                ),
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.TRANSPORT_ORDER,
                        "Заявка ORD-1",
                        true
                )),
                List.of(),
                true
        );

        GuardrailResult result = service.check(request, response);
        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("confirmed by dispatcher"));
    }


    @Test
    void blocksWhenLegalContextExistsButModelReturnsNoLawArticles() {
        GenerateClaimResponse base = validPaymentResponse(validText());
        GenerateClaimResponse response = new GenerateClaimResponse(
                base.claimType(),
                base.claimText(),
                base.summaryForLawyer(),
                base.usedContractClauses(),
                List.of(),
                base.backendCalculationUsed(),
                base.attachments(),
                base.warnings(),
                base.manualReviewRequired()
        );

        GuardrailResult result = service.check(paymentRequest(true), response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("must cite at least one applicable law article"));
    }

    @Test
    void blocksWhenUsedLawArticleIsMissingFromClaimText() {
        String textWithoutCitation = validText().replace("Правовое основание: ст. 309 ГК РФ.", "");

        GuardrailResult result = service.check(
                paymentRequest(true),
                validPaymentResponse(textWithoutCitation)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("does not cite used law article"));
    }


    @Test
    void acceptsEquivalentNaturalOrderForCanonicalLegalCitation() {
        // Use the production-shaped fixture so this test checks legal citation
        // normalization without being coupled to older/minimal claim fixtures.
        GenerateClaimRequest base = productionPaymentRequest();
        GenerateClaimRequest request = new GenerateClaimRequest(
                base.caseFacts(),
                base.backendCalculation(),
                base.contractContext(),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "law-309", "ГК РФ (часть первая)", "309", "надлежащее исполнение",
                        "Обязательства исполняются надлежащим образом",
                        "ГК РФ, ст. 309", "2026-08-06", "PAYMENT_DELAY"
                )),
                base.templateContext(),
                base.similarExamples()
        );

        GenerateClaimResponse response = productionPaymentResponse(
                productionPaymentText().replace("Правовое основание: ст. 309 ГК РФ.",
                        "Правовое основание: в соответствии со ст. 309 ГК РФ обязательства исполняются надлежащим образом.")
        );

        GuardrailResult result = service.check(request, response);

        assertThat(result.errors())
                .as("guardrail errors: %s", result.errors())
                .isEmpty();
        assertThat(result.decision()).isNotEqualTo(GuardrailDecision.BLOCK);
    }

    @Test
    void blocksLawArticleMentionedOutsideLegalContext() {
        String text = validText() + " Дополнительно применена ст. 999 ГК РФ.";

        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("not present in legal_context: 999"));
    }

    @Test
    void blocksWhenLegalContextIsEmpty() {
        GenerateClaimRequest base = paymentRequest(true);
        GenerateClaimRequest request = new GenerateClaimRequest(
                base.caseFacts(),
                base.backendCalculation(),
                base.contractContext(),
                List.of(),
                base.templateContext(),
                base.similarExamples()
        );

        GuardrailResult result = service.check(request, validPaymentResponse(validText()));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("legal_context is required"));
    }

    @Test
    void passesProductionPaymentClaimWithHeaderDeadlineSignatureAndActWithoutNumber() {
        GenerateClaimRequest request = productionPaymentRequest();
        GenerateClaimResponse response = productionPaymentResponse(productionPaymentText());

        GuardrailResult result = service.check(request, response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksDanglingActNumberWhenSourceActNumberIsMissing() {
        String text = productionPaymentText().replace("актом от 01.05.2026", "актом № от 01.05.2026");

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("dangling act number marker"));
    }

    @Test
    void blocksMissingOutgoingClaimNumber() {
        String text = productionPaymentText().replace("Исх. № CLM-2026-001 от 10.06.2026.\n", "");

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("claim_number"));
    }

    @Test
    void blocksMissingContractualResponseDeadline() {
        String text = productionPaymentText().replace(
                "Требуем оплатить задолженность и направить письменный ответ в течение 10 календарных дней с даты получения настоящей претензии.\n",
                "Требуем оплатить задолженность.\n"
        );

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("contract.claim_response_days"));
    }

    @Test
    void allowsAttachmentsSectionButBlocksUnsupportedBankDetailsInClaimText() {
        String text = productionPaymentText()
                + "\nПриложения:\n1. Копия договора.\n"
                + "Расчетный счет 40702810000000000000, БИК 044525000.";

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("bank details"));
    }

    @Test
    void blocksMachineFormattingAndTechnicalEnumsInClaimText() {
        String text = productionPaymentText()
                .replace("10.06.2026", "2026-06-10")
                .replace("Основной долг составляет 240 000 руб.", "Основной долг составляет 240000.00 рублей.")
                + "\nТехнический статус платежа: UNPAID.";

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("machine ISO date"));
        assertThat(result.errors()).anyMatch(error -> error.contains("technical enum/code"));
        assertThat(result.errors()).anyMatch(error -> error.contains("machine-formatted RUB amount"));
    }

    private GenerateClaimRequest productionPaymentRequest() {
        GenerateClaimRequest base = paymentRequest(true);
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        base.caseFacts().claimId(),
                        "CLM-2026-001",
                        base.caseFacts().claimType(),
                        base.caseFacts().creditor(),
                        base.caseFacts().debtor(),
                        new GenerateClaimRequest.ContractFacts("45/2026", "10.01.2026", 10),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-157",
                                "Санкт-Петербург — Москва",
                                null,
                                "01.05.2026",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ),
                        base.caseFacts().payment(),
                        "10.06.2026",
                        new GenerateClaimRequest.SignatoryFacts("Дмитриев Павел Алексеевич", "Юрист")
                ),
                base.backendCalculation(),
                base.contractContext(),
                base.legalContext(),
                base.templateContext(),
                base.similarExamples()
        );
    }

    private GenerateClaimResponse productionPaymentResponse(String text) {
        GenerateClaimResponse base = validPaymentResponse(text);
        return new GenerateClaimResponse(
                base.claimType(),
                base.claimText(),
                base.summaryForLawyer(),
                base.usedContractClauses(),
                base.usedLawArticles(),
                base.backendCalculationUsed(),
                List.of(),
                base.warnings(),
                base.manualReviewRequired()
        );
    }

    private String productionPaymentText() {
        return """
                Исх. № CLM-2026-001 от 10.06.2026.
                Претензия о нарушении срока оплаты оказанных услуг.
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Клиент, ИНН 7700000000.
                По договору №45/2026 от 10.01.2026 оказаны услуги по маршруту Санкт-Петербург — Москва.
                Оказание услуг подтверждено актом от 01.05.2026.
                Срок оплаты истёк 31.05.2026. Основной долг составляет 240 000 руб.,
                неустойка — 2 400 руб., итого к оплате — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ.
                Требуем оплатить задолженность и направить письменный ответ в течение 10 календарных дней с даты получения настоящей претензии.
                Юрист __________ Дмитриев Павел Алексеевич
                """;
    }

    private GenerateClaimRequest detailedLoadingRequest() {
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim-lf-detailed",
                        GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                        new GenerateClaimRequest.Party("ООО Клиент-Заказчик", "7700000000", "Москва"),
                        new GenerateClaimRequest.Party("ООО Перевозчик", "7800000000", "Санкт-Петербург"),
                        new GenerateClaimRequest.ContractFacts("LF-77/2026", "05.02.2026"),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-LF-200", "Москва - Казань", "ACT-LF-200", "12.06.2026", null, null,
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
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "chunk_lf_contract_001", "5.1", "Подача ТС", "Перевозчик обязан предоставить ТС"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "chunk_lf_contract_002", "6.4", "Штраф", "Штраф 15000 рублей"
                        )
                ),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "chunk_lf_legal_gk_330", "ГК РФ", "330", "неустойка", "Понятие неустойки",
                        "ст. 330 ГК РФ", "2026-07-30", "LOADING_FAILURE"
                )),
                new GenerateClaimRequest.TemplateContext(
                        "tpl-lf", "Претензия", GenerateClaimRequest.ClaimType.LOADING_FAILURE, List.of("Факты", "Требование")
                ),
                List.of()
        );
    }

    private GenerateClaimResponse detailedLoadingResponse(String text) {
        return new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                text,
                "Претензия за непредоставление транспортного средства",
                List.of(
                        new GenerateClaimResponse.UsedContractClause(
                                "5.1", "chunk_lf_contract_001", "обязанность предоставить ТС"
                        ),
                        new GenerateClaimResponse.UsedContractClause(
                                "6.4", "chunk_lf_contract_002", "штраф за непредоставление ТС"
                        )
                ),
                List.of(new GenerateClaimResponse.UsedLawArticle(
                        "chunk_lf_legal_gk_330", "ГК РФ", "330", "основание неустойки"
                )),
                new GenerateClaimResponse.BackendCalculationUsed(
                        BigDecimal.ZERO,
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("15000"),
                        new BigDecimal("15000"),
                        0,
                        "RUB"
                ),
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.LOADING_FAILURE_ACT,
                        "Акт о срыве погрузки № ACT-LF-200 от 12.06.2026",
                        true
                )),
                List.of(),
                true
        );
    }

    private String detailedLoadingText() {
        return """
                От: ООО Клиент-Заказчик, ИНН 7700000000, Москва.
                Кому: ООО Перевозчик, ИНН 7800000000, Санкт-Петербург.
                Претензия по договору № LF-77/2026 от 05.02.2026.
                По заявке № ORD-LF-200 от 12.06.2026 требовался тент 20 т по маршруту Москва-Казань.
                Погрузка была назначена на складе №4 в Москве с 09:00 до 12:00.
                Факт непредоставления транспортного средства подтверждён актом № ACT-LF-200 от 12.06.2026.
                Правовое основание: ст. 330 ГК РФ.
                На основании нарушения просим оплатить штраф 15 000 руб.
                """;
    }

    private GenerateClaimRequest paymentRequest(boolean confirmed) {
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "claim-1",
                        GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                        new GenerateClaimRequest.Party("ООО Экспедитор", "7800000000", "Санкт-Петербург"),
                        new GenerateClaimRequest.Party("ООО Клиент", "7700000000", "Москва"),
                        new GenerateClaimRequest.ContractFacts("45/2026", "10.01.2026"),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-157", "Санкт-Петербург — Москва", "157", "01.05.2026", "ТТН-157", "INV-157"
                        ),
                        new GenerateClaimRequest.PaymentFacts(
                                "31.05.2026", GenerateClaimRequest.PaymentStatus.UNPAID, confirmed
                        ),
                        "10.06.2026"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        "0,1% в день",
                        10,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        "RUB",
                        "240000 × 0,1% × 10"
                ),
                List.of(new GenerateClaimRequest.ContractContextChunk(
                        "contract-payment", "4.2", "Оплата", "Оплата в течение 30 дней"
                )),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "law-309", "ГК РФ", "309", "надлежащее исполнение", "Обязательства исполняются надлежащим образом",
                        "ст. 309 ГК РФ", "2026-07-30", "PAYMENT_DELAY"
                )),
                new GenerateClaimRequest.TemplateContext(
                        "tpl", "Претензия", GenerateClaimRequest.ClaimType.PAYMENT_DELAY, List.of("Факты", "Требование")
                ),
                List.of()
        );
    }

    private GenerateClaimRequest loadingRequest(boolean confirmed) {
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        "lf-1",
                        GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                        new GenerateClaimRequest.Party("ООО Экспедитор", "7800000000", "Санкт-Петербург"),
                        new GenerateClaimRequest.Party("ООО Перевозчик", "7700000000", "Москва"),
                        new GenerateClaimRequest.ContractFacts("LF-1", "01.06.2026"),
                        new GenerateClaimRequest.ShipmentFacts(
                                "ORD-1", "Москва — Тверь", null, null, null, null,
                                "10.06.2026", "Москва", null, null, "ООО Перевозчик", confirmed
                        ),
                        null,
                        "10.06.2026"
                ),
                new GenerateClaimRequest.BackendCalculation(
                        BigDecimal.ZERO,
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        "фиксированный штраф",
                        0,
                        new BigDecimal("20000"),
                        new BigDecimal("20000"),
                        "RUB",
                        "20000"
                ),
                List.of(new GenerateClaimRequest.ContractContextChunk(
                        "lf-duty", "5.1", "Подача ТС", "Перевозчик обязан предоставить ТС"
                )),
                List.of(new GenerateClaimRequest.LegalContextItem(
                        "law-lf", "УАТ", "34", "ответственность", "Ответственность за неподачу ТС",
                        "ч. 1 ст. 34 УАТ", "2026-07-30", "LOADING_FAILURE"
                )),
                new GenerateClaimRequest.TemplateContext(
                        "tpl-lf", "Претензия", GenerateClaimRequest.ClaimType.LOADING_FAILURE, List.of("Факты", "Требование")
                ),
                List.of()
        );
    }

    private GenerateClaimResponse validPaymentResponse(String text) {
        return new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                text,
                "Просрочка оплаты по договору",
                List.of(new GenerateClaimResponse.UsedContractClause("4.2", "contract-payment", "срок оплаты")),
                List.of(new GenerateClaimResponse.UsedLawArticle("law-309", "ГК РФ", "309", "надлежащее исполнение")),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        10,
                        "RUB"
                ),
                List.of(
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.CONTRACT, "Договор 45/2026", true),
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.ACT, "Акт 157", true),
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.TTN, "ТТН-157", true),
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.INVOICE, "INV-157", true),
                        new GenerateClaimResponse.Attachment(GenerateClaimResponse.DocumentType.CALCULATION, "Расчёт", true)
                ),
                List.of(),
                true
        );
    }

    private String validText() {
        return """
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Клиент, ИНН 7700000000.
                Претензия по договору №45/2026 от 10.01.2026.
                Перевозка по маршруту Санкт-Петербург — Москва, заказ ORD-157.
                Услуги подтверждены актом №157 от 01.05.2026, ТТН-157 и счётом INV-157.
                Срок оплаты истёк 31.05.2026. Основной долг составляет 240 000 руб.,
                неустойка — 2 400 руб., итого к оплате — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ.
                """;
    }
}

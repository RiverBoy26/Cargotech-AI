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
    void passesWhenOptionalDocumentReferencesArePresentOnlyInClaimText() {
        String text = """
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Клиент, ИНН 7700000000.
                Претензия по п. 4.2 Договора №45/2026 от 10.01.2026.
                Перевозка по маршруту Санкт-Петербург — Москва.
                Услуги подтверждены актом №157 от 01.05.2026.
                Срок оплаты истёк 31.05.2026. Основной долг составляет 240 000 руб.,
                неустойка — 2 400 руб., итого к оплате — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ.
                """;

        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).withFailMessage("Guardrail errors: %s", result.errors())
                .isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksPaymentDelayAttachmentsRegardlessOfDocumentType() {
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
        assertThat(result.errors()).anyMatch(error -> error.contains("attachments must be empty"));
    }

    @Test
    void blocksUnknownInnInsideClaimText() {
        String text = validText() + " Дополнительный получатель: ИНН 7812345678.";
        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("unknown INN"));
    }

    @Test
    void acceptsRussianRublesAndKopecksAsExactBackendAmounts() {
        String text = validText()
                .replace("240 000 руб.", "240 000 рублей 00 копеек")
                .replace("2 400 руб.", "2 400 рублей 00 копеек")
                .replace("242 400 руб.", "242 400 рублей 00 копеек");

        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).withFailMessage("Guardrail errors: %s", result.errors())
                .isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void acceptsNonZeroKopecksWithoutInventingWholeRubleAmounts() {
        GenerateClaimRequest base = paymentRequest(true);
        GenerateClaimRequest request = new GenerateClaimRequest(
                base.caseFacts(),
                new GenerateClaimRequest.BackendCalculation(
                        new BigDecimal("250000.00"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        "ст. 395 ГК РФ",
                        50,
                        new BigDecimal("5698.63"),
                        new BigDecimal("255698.63"),
                        "RUB",
                        "backend"
                ),
                base.contractContext(),
                base.legalContext(),
                base.templateContext(),
                base.similarExamples()
        );

        String text = validText()
                // Replace the total before the penalty: "242 400" contains
                // "2 400" as a substring, so the opposite order corrupts
                // the fixture into "245 698 ...".
                .replace("242 400 руб.", "255 698 рублей 63 копейки")
                .replace("240 000 руб.", "250 000 рублей 00 копеек")
                .replace("2 400 руб.", "5 698 рублей 63 копейки");

        GenerateClaimResponse response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                text,
                "Просрочка оплаты по договору",
                List.of(new GenerateClaimResponse.UsedContractClause("4.2", "contract-payment", "срок оплаты")),
                List.of(new GenerateClaimResponse.UsedLawArticle("law-309", "ГК РФ", "309", "надлежащее исполнение")),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("250000.00"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        new BigDecimal("5698.63"),
                        new BigDecimal("255698.63"),
                        50,
                        "RUB"
                ),
                List.of(),
                List.of(),
                true
        );

        GuardrailResult result = service.check(request, response);

        assertThat(result.errors()).noneMatch(error -> error.contains("5698 RUB"));
        assertThat(result.errors()).noneMatch(error -> error.contains("255698 RUB"));
        assertThat(result.errors()).noneMatch(error -> error.contains("expected total_amount"));
    }

    @Test
    void blocksPaymentDelayWhenContractClauseMetadataIsNotCitedInClaimText() {
        String text = validText().replace("п. 4.2 Договора", "Договора");

        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("does not cite used contract clause"));
    }

    @Test
    void blocksPaymentDelayAttachmentsInCurrentScope() {
        GenerateClaimResponse base = validPaymentResponse(validText());
        GenerateClaimResponse response = new GenerateClaimResponse(
                base.claimType(),
                base.claimText(),
                base.summaryForLawyer(),
                base.usedContractClauses(),
                base.usedLawArticles(),
                base.backendCalculationUsed(),
                List.of(new GenerateClaimResponse.Attachment(
                        GenerateClaimResponse.DocumentType.CALCULATION,
                        "Расчёт задолженности",
                        true
                )),
                base.warnings(),
                base.manualReviewRequired()
        );

        GuardrailResult result = service.check(paymentRequest(true), response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("attachments must be empty"));
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
        assertThat(result.decision()).withFailMessage("Guardrail errors: %s", result.errors())
                .isEqualTo(GuardrailDecision.PASS);
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
    void blocksLoadingFailureAttachmentsInCurrentScope() {
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
                        true
                )),
                base.warnings(),
                base.manualReviewRequired()
        );

        GuardrailResult result = service.check(detailedLoadingRequest(), response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("attachments must be empty"));
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
                List.of(),
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
    void acceptsGroupedContractAndLawCitations() {
        GenerateClaimRequest base = paymentRequest(true);
        GenerateClaimRequest request = new GenerateClaimRequest(
                base.caseFacts(),
                base.backendCalculation(),
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "contract-payment", "4.2", "Оплата", "Срок оплаты"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "contract-payment-extra", "4.3", "Расчеты", "Порядок расчетов"
                        )
                ),
                List.of(
                        new GenerateClaimRequest.LegalContextItem(
                                "law-309", "ГК РФ", "309", "надлежащее исполнение",
                                "Обязательства исполняются надлежащим образом",
                                "ст. 309 ГК РФ", "2026-08-13", "PAYMENT_DELAY"
                        ),
                        new GenerateClaimRequest.LegalContextItem(
                                "law-314", "ГК РФ", "314", "срок исполнения",
                                "Обязательство исполняется в установленный срок",
                                "ст. 314 ГК РФ", "2026-08-13", "PAYMENT_DELAY"
                        )
                ),
                base.templateContext(),
                base.similarExamples()
        );

        String text = validText()
                .replace("п. 4.2 Договора", "п. 4.2, 4.3 Договора")
                .replace("ст. 309 ГК РФ", "ст. 309, 314 ГК РФ");

        GenerateClaimResponse response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                text,
                "Просрочка оплаты по договору",
                List.of(
                        new GenerateClaimResponse.UsedContractClause(
                                "4.2", "contract-payment", "срок оплаты"
                        ),
                        new GenerateClaimResponse.UsedContractClause(
                                "4.3", "contract-payment-extra", "порядок расчетов"
                        )
                ),
                List.of(
                        new GenerateClaimResponse.UsedLawArticle(
                                "law-309", "ГК РФ", "309", "надлежащее исполнение"
                        ),
                        new GenerateClaimResponse.UsedLawArticle(
                                "law-314", "ГК РФ", "314", "срок исполнения"
                        )
                ),
                validPaymentResponse(text).backendCalculationUsed(),
                List.of(),
                List.of(),
                true
        );

        GuardrailResult result = service.check(request, response);

        assertThat(result.decision())
                .withFailMessage("Guardrail errors: %s", result.errors())
                .isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksInventedOrderDateForPaymentDelay() {
        String text = productionPaymentText()
                .replace("оказаны услуги по маршруту", "по заказу № ORD-157 от 1 июня 2026 года оказаны услуги по маршруту");

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("invents an order/application date"));
    }

    @Test
    void blocksAccountantConfirmationOverclaim() {
        String text = validText()
                + " Бухгалтером подтвержден факт выставления документов и наступления срока платежа.";

        GuardrailResult result = service.check(paymentRequest(true), validPaymentResponse(text));

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("overstates accountant confirmation"));
    }

    @Test
    void blocksResponseDeadlineUsedAsPaymentDeadline() {
        String text = productionPaymentText()
                .replace(
                        "Требуем оплатить задолженность.\nПисьменный ответ направить в течение 10 календарных дней",
                        "Требуем оплатить задолженность в течение 10 календарных дней.\nПисьменный ответ направить в течение 10 календарных дней"
                );

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("uses contract.claim_response_days as a payment deadline"));
    }

    @Test
    void blocksLegalInterestDescribedAsPenalty() {
        GenerateClaimRequest base = paymentRequest(true);
        GenerateClaimRequest request = new GenerateClaimRequest(
                base.caseFacts(),
                new GenerateClaimRequest.BackendCalculation(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        "ст. 395 ГК РФ",
                        10,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        "RUB",
                        "backend"
                ),
                base.contractContext(),
                base.legalContext(),
                base.templateContext(),
                base.similarExamples()
        );

        GenerateClaimResponse baseResponse = validPaymentResponse(validText());
        GenerateClaimResponse response = new GenerateClaimResponse(
                baseResponse.claimType(),
                baseResponse.claimText(),
                baseResponse.summaryForLawyer(),
                baseResponse.usedContractClauses(),
                baseResponse.usedLawArticles(),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        10,
                        "RUB"
                ),
                List.of(),
                baseResponse.warnings(),
                true
        );

        GuardrailResult result = service.check(request, response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("describes LEGAL_INTEREST"));
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
                "Письменный ответ направить в течение 10 календарных дней с даты получения настоящей претензии.\n",
                ""
        );

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("contract.claim_response_days"));
    }

    @Test
    void blocksAttachmentsSectionAndBankDetailsInCurrentScope() {
        String text = productionPaymentText()
                + "\nПриложения:\n1. Копия договора.\n"
                + "Расчетный счет 40702810000000000000, БИК 044525000.";

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("attachments section"));
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


    @Test
    void passesTypedContractClausesWhenEachRequirementUsesItsOwnClause() {
        GenerateClaimRequest request = typedPaymentRequest(GenerateClaimRequest.TermDayType.CALENDAR_DAYS);
        GuardrailResult result = service.check(request, typedPaymentResponse(typedPaymentText("календарных")));

        assertThat(result.decision()).withFailMessage("Guardrail errors: %s", result.errors())
                .isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksGroupedContractClausesWithDifferentSemanticRoles() {
        String text = typedPaymentText("календарных").replace(
                "В соответствии с п. 9.4 Договора №45/2026 от 10.01.2026 начислена договорная неустойка — 2 400 руб.",
                "В соответствии с п. 8.2, 9.4 Договора №45/2026 от 10.01.2026 начислена договорная неустойка — 2 400 руб."
        );

        GuardrailResult result = service.check(
                typedPaymentRequest(GenerateClaimRequest.TermDayType.CALENDAR_DAYS),
                typedPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("different semantic roles"));
    }

    @Test
    void blocksWrongResponseDayTypeEvenWhenNumberMatches() {
        GuardrailResult result = service.check(
                typedPaymentRequest(GenerateClaimRequest.TermDayType.WORKING_DAYS),
                typedPaymentResponse(typedPaymentText("календарных"))
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("contract.claim_response_days"));
    }


    @Test
    void blocksDemandSectionThatCollapsesDebtAndPenaltyIntoOnlyTotal() {
        String text = typedPaymentText("календарных")
                .replace(
                        "1. Уплатить основной долг — 240 000 руб.\n"
                                + "2. Уплатить договорную неустойку — 2 400 руб.\n"
                                + "Всего — 242 400 руб.",
                        "1. Оплатить общую сумму задолженности — 242 400 руб."
                );

        GuardrailResult result = service.check(
                typedPaymentRequest(GenerateClaimRequest.TermDayType.CALENDAR_DAYS),
                typedPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("principal debt separately"));
        assertThat(result.errors()).anyMatch(error -> error.contains("sanction separately"));
    }

    @Test
    void acceptsDemandSectionWithTotalAndBreakdownInOneSentence() {
        String text = typedPaymentText("календарных")
                .replace(
                        "1. Уплатить основной долг — 240 000 руб.\n"
                                + "2. Уплатить договорную неустойку — 2 400 руб.\n"
                                + "Всего — 242 400 руб.",
                        "1. Произвести оплату задолженности в размере 242 400 руб., "
                                + "в том числе основной долг в размере 240 000 руб. "
                                + "и договорную неустойку в размере 2 400 руб."
                );

        GuardrailResult result = service.check(
                typedPaymentRequest(GenerateClaimRequest.TermDayType.CALENDAR_DAYS),
                typedPaymentResponse(text)
        );

        assertThat(result.decision()).withFailMessage("Guardrail errors: %s", result.errors())
                .isEqualTo(GuardrailDecision.PASS);
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void blocksFirstPersonSingularDemandForLegalEntityCreditor() {
        String text = typedPaymentText("календарных")
                .replace("ООО Экспедитор требует:", "Требую:");

        GuardrailResult result = service.check(
                typedPaymentRequest(GenerateClaimRequest.TermDayType.CALENDAR_DAYS),
                typedPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("first-person singular"));
    }

    @Test
    void blocksArticle395ForContractPenaltyWhenArticle330IsAvailable() {
        String text = typedPaymentText("календарных")
                .replace("ст. 330 ГК РФ", "ст. 395 ГК РФ");
        GenerateClaimResponse response = new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                text,
                "Просрочка оплаты по договору",
                List.of(
                        new GenerateClaimResponse.UsedContractClause("8.2", "typed-payment", "срок оплаты"),
                        new GenerateClaimResponse.UsedContractClause("9.4", "typed-penalty", "договорная неустойка"),
                        new GenerateClaimResponse.UsedContractClause("10.2", "typed-response", "срок ответа")
                ),
                List.of(
                        new GenerateClaimResponse.UsedLawArticle("law-309", "ГК РФ", "309", "надлежащее исполнение"),
                        new GenerateClaimResponse.UsedLawArticle("law-395", "ГК РФ", "395", "ошибочная квалификация")
                ),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        10,
                        "RUB"
                ),
                List.of(),
                List.of(),
                true
        );

        GenerateClaimRequest base = typedPaymentRequest(GenerateClaimRequest.TermDayType.CALENDAR_DAYS);
        GenerateClaimRequest request = new GenerateClaimRequest(
                base.caseFacts(),
                base.backendCalculation(),
                base.contractContext(),
                List.of(
                        new GenerateClaimRequest.LegalContextItem(
                                "law-309", "ГК РФ", "309", "надлежащее исполнение",
                                "Обязательства исполняются надлежащим образом",
                                "ст. 309 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                        ),
                        new GenerateClaimRequest.LegalContextItem(
                                "law-330", "ГК РФ", "330", "договорная неустойка",
                                "Неустойка устанавливается законом или договором",
                                "ст. 330 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                        ),
                        new GenerateClaimRequest.LegalContextItem(
                                "law-395", "ГК РФ", "395", "проценты",
                                "Проценты за пользование чужими денежными средствами",
                                "ст. 395 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                        )
                ),
                base.templateContext(),
                base.similarExamples()
        );

        GuardrailResult result = service.check(request, response);

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("Article 330"));
        assertThat(result.errors()).anyMatch(error -> error.contains("Article 395"));
    }


    private GenerateClaimRequest typedPaymentRequest(GenerateClaimRequest.TermDayType responseDayType) {
        GenerateClaimRequest base = productionPaymentRequest();
        return new GenerateClaimRequest(
                new GenerateClaimRequest.CaseFacts(
                        base.caseFacts().claimId(),
                        base.caseFacts().claimNumber(),
                        base.caseFacts().claimType(),
                        base.caseFacts().creditor(),
                        base.caseFacts().debtor(),
                        new GenerateClaimRequest.ContractFacts(
                                "45/2026", "10.01.2026", 10, responseDayType, "contract-doc-1"
                        ),
                        base.caseFacts().shipment(),
                        base.caseFacts().payment(),
                        base.caseFacts().claimDate(),
                        base.caseFacts().signatory()
                ),
                base.backendCalculation(),
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "typed-payment", "8.2", "Срок оплаты", "PAYMENT_TERMS",
                                "Оплата должна быть произведена в течение 30 календарных дней."
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "typed-penalty", "9.4", "Неустойка", "PENALTY",
                                "Неустойка 0,1 % за каждый календарный день просрочки."
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "typed-response", "10.2", "Претензионный порядок", "CLAIM_PROCEDURE",
                                "Ответ направляется в течение 10 дней с даты получения претензии."
                        )
                ),
                List.of(
                        new GenerateClaimRequest.LegalContextItem(
                                "law-309", "ГК РФ", "309", "надлежащее исполнение",
                                "Обязательства исполняются надлежащим образом",
                                "ст. 309 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                        ),
                        new GenerateClaimRequest.LegalContextItem(
                                "law-330", "ГК РФ", "330", "договорная неустойка",
                                "Неустойка устанавливается законом или договором",
                                "ст. 330 ГК РФ", "2026-08-15", "PAYMENT_DELAY"
                        )
                ),
                base.templateContext(),
                base.similarExamples()
        );
    }

    private GenerateClaimResponse typedPaymentResponse(String text) {
        return new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                text,
                "Просрочка оплаты по договору",
                List.of(
                        new GenerateClaimResponse.UsedContractClause("8.2", "typed-payment", "срок оплаты"),
                        new GenerateClaimResponse.UsedContractClause("9.4", "typed-penalty", "договорная неустойка"),
                        new GenerateClaimResponse.UsedContractClause("10.2", "typed-response", "срок ответа")
                ),
                List.of(
                        new GenerateClaimResponse.UsedLawArticle("law-309", "ГК РФ", "309", "надлежащее исполнение"),
                        new GenerateClaimResponse.UsedLawArticle("law-330", "ГК РФ", "330", "договорная неустойка")
                ),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("240000"),
                        GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                        new BigDecimal("2400"),
                        new BigDecimal("242400"),
                        10,
                        "RUB"
                ),
                List.of(),
                List.of(),
                true
        );
    }

    private String typedPaymentText(String responseDayUnit) {
        return """
                Исх. № CLM-2026-001 от 10.06.2026.
                Претензия о нарушении срока оплаты оказанных услуг.
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Клиент, ИНН 7700000000.
                Услуги по маршруту Санкт-Петербург — Москва подтверждены актом от 01.05.2026.
                Согласно п. 8.2 Договора №45/2026 от 10.01.2026 срок оплаты истёк 31.05.2026.
                Основной долг составляет 240 000 руб.
                В соответствии с п. 9.4 Договора №45/2026 от 10.01.2026 начислена договорная неустойка — 2 400 руб.
                Общая сумма требований — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ и ст. 330 ГК РФ.
                ООО Экспедитор требует:
                1. Уплатить основной долг — 240 000 руб.
                2. Уплатить договорную неустойку — 2 400 руб.
                Всего — 242 400 руб.
                В соответствии с п. 10.2 Договора №45/2026 от 10.01.2026 письменный ответ направить в течение 10 %s дней с даты получения настоящей претензии.
                Юрист __________ Дмитриев Павел Алексеевич
                """.formatted(responseDayUnit);
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
                По п. 4.2 Договора №45/2026 от 10.01.2026 оказаны услуги по маршруту Санкт-Петербург — Москва.
                Оказание услуг подтверждено актом от 01.05.2026.
                Срок оплаты истёк 31.05.2026. Основной долг составляет 240 000 руб.,
                неустойка — 2 400 руб., итого к оплате — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ.
                Требуем оплатить задолженность.
                Письменный ответ направить в течение 10 календарных дней с даты получения настоящей претензии.
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
                List.of(),
                List.of(),
                true
        );
    }

    private String detailedLoadingText() {
        return """
                От: ООО Клиент-Заказчик, ИНН 7700000000, Москва.
                Кому: ООО Перевозчик, ИНН 7800000000, Санкт-Петербург.
                Претензия по договору № LF-77/2026 от 05.02.2026.
                Согласно п. 5.1 Договора перевозчик обязан предоставить транспортное средство.
                По заявке № ORD-LF-200 от 12.06.2026 требовался тент 20 т по маршруту Москва-Казань.
                Погрузка была назначена на складе №4 в Москве с 09:00 до 12:00.
                Факт непредоставления транспортного средства подтверждён актом № ACT-LF-200 от 12.06.2026.
                Правовое основание: ст. 330 ГК РФ.
                На основании п. 6.4 Договора и допущенного нарушения просим оплатить штраф 15 000 руб.
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
                List.of(),
                List.of(),
                true
        );
    }

    private String validText() {
        return """
                От: ООО Экспедитор, ИНН 7800000000.
                Кому: ООО Клиент, ИНН 7700000000.
                Претензия по п. 4.2 Договора №45/2026 от 10.01.2026.
                Перевозка по маршруту Санкт-Петербург — Москва, заказ ORD-157.
                Услуги подтверждены актом №157 от 01.05.2026, ТТН-157 и счётом INV-157.
                Срок оплаты истёк 31.05.2026. Основной долг составляет 240 000 руб.,
                неустойка — 2 400 руб., итого к оплате — 242 400 руб.
                Правовое основание: ст. 309 ГК РФ.
                """;
    }

    @Test
    void blocksUnsupportedPartyHeaderNoiseForPaymentDelay() {
        String text = productionPaymentText()
                .replace(
                        "От: ООО Экспедитор, ИНН 7800000000.",
                        "г. Барнаул\nОт: ООО Экспедитор, ИНН 7800000000, адрес: Санкт-Петербург,\n"
                                + "в лице Юриста Дмитриев Павел Алексеевич,\n"
                                + "адрес: Санкт-Петербург,\nтелефон:"
                )
                .replace(
                        "Кому: ООО Клиент, ИНН 7700000000.",
                        "Кому: ООО Клиент, ИНН 7700000000, адрес: Москва,\n"
                                + "в лице уполномоченного лица,\nадрес: Москва,\nтелефон:"
                );

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains("unsupported contact field"));
        assertThat(result.errors()).anyMatch(error -> error.contains("unsupported party representative"));
        assertThat(result.errors()).anyMatch(error -> error.contains("standalone document place"));
        assertThat(result.errors()).anyMatch(error -> error.contains("duplicates party addresses"));
    }

    @Test
    void blocksPaymentClauseCitationWithoutContractNumberInSameSentence() {
        String text = productionPaymentText().replace(
                "По п. 4.2 Договора №45/2026 от 10.01.2026 оказаны услуги",
                "По п. 4.2 Договора оказаны услуги"
        );

        GuardrailResult result = service.check(
                productionPaymentRequest(),
                productionPaymentResponse(text)
        );

        assertThat(result.decision()).isEqualTo(GuardrailDecision.BLOCK);
        assertThat(result.errors()).anyMatch(error -> error.contains(
                "contract clause citation must include contract number"
        ));
    }

}

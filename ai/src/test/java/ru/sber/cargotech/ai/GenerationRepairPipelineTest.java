package ru.sber.cargotech.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import ru.sber.cargotech.ai.claim.ClaimGenerationPipelineService;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimPipelineRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimResponse;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailDecision;
import ru.sber.cargotech.ai.claim.guardrail.GuardrailResult;
import ru.sber.cargotech.ai.claim.guardrail.RuleBasedGuardrailService;
import ru.sber.cargotech.ai.claim.parser.ClaimResponseParser;
import ru.sber.cargotech.ai.claim.prompt.LoadingFailurePromptBuilder;
import ru.sber.cargotech.ai.claim.prompt.PaymentDelayPromptBuilder;
import ru.sber.cargotech.ai.document.DocumentGenerationPipelineService;
import ru.sber.cargotech.ai.document.dto.GenerateDocumentResponse;
import ru.sber.cargotech.ai.document.guardrail.DocumentGuardrailService;
import ru.sber.cargotech.ai.document.parser.DocumentResponseParser;
import ru.sber.cargotech.ai.document.prompt.LoadingFailureActPromptBuilder;
import ru.sber.cargotech.ai.document.prompt.LoadingFailureNotificationPromptBuilder;
import ru.sber.cargotech.ai.gigachat.GigaChatClient;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.rag.RagSearchService;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationRepairPipelineTest {

    @Test
    void claimPipelineRetriesOnceAndAggregatesUsageAfterGuardrailBlock() {
        RagSearchService rag = mock(RagSearchService.class);
        PaymentDelayPromptBuilder paymentPrompt = mock(PaymentDelayPromptBuilder.class);
        LoadingFailurePromptBuilder loadingPrompt = mock(LoadingFailurePromptBuilder.class);
        GigaChatClient client = mock(GigaChatClient.class);
        ClaimResponseParser parser = mock(ClaimResponseParser.class);
        RuleBasedGuardrailService guardrails = mock(RuleBasedGuardrailService.class);

        when(loadingPrompt.build(any(GenerateClaimRequest.class))).thenReturn(List.of(
                new GigaChatMessage("system", "system"),
                new GigaChatMessage("user", "user")
        ));

        GigaChatClient.ChatCallResult firstCall = new GigaChatClient.ChatCallResult(
                "first",
                response("bad", 100, 20, 120)
        );
        GigaChatClient.ChatCallResult secondCall = new GigaChatClient.ChatCallResult(
                "second",
                response("good", 1, 25, 26)
        );
        when(client.sendChatWithTrace(anyList(), anyString(), any(), anyString(), anyLong()))
                .thenReturn(firstCall)
                .thenReturn(secondCall);

        GenerateClaimResponse bad = mock(GenerateClaimResponse.class);
        GenerateClaimResponse good = mock(GenerateClaimResponse.class);
        when(parser.parse("bad")).thenReturn(bad);
        when(parser.parse("good")).thenReturn(good);
        when(guardrails.check(any(GenerateClaimRequest.class), same(bad)))
                .thenReturn(new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("claim_text does not contain expected shipment.order_number: ORD-LF-200"),
                        List.of()
                ));
        when(guardrails.check(any(GenerateClaimRequest.class), same(good)))
                .thenReturn(new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of()));

        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                rag,
                paymentPrompt,
                loadingPrompt,
                client,
                parser,
                guardrails
        );

        var result = service.generate(request());

        assertThat(result.success()).isEqualTo(true);
        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.requestId()).isEqualTo("second");
        assertThat(result.tokenUsage().promptTokens()).isEqualTo(101);
        assertThat(result.tokenUsage().completionTokens()).isEqualTo(45);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(146);
        verify(client, times(2)).sendChatWithTrace(anyList(), anyString(), any(), anyString(), anyLong());
    }



    @Test
    void claimPipelineReturnsBlockedDraftWhenBoundedRepairTimesOut() {
        RagSearchService rag = mock(RagSearchService.class);
        PaymentDelayPromptBuilder paymentPrompt = mock(PaymentDelayPromptBuilder.class);
        LoadingFailurePromptBuilder loadingPrompt = mock(LoadingFailurePromptBuilder.class);
        GigaChatClient client = mock(GigaChatClient.class);
        ClaimResponseParser parser = mock(ClaimResponseParser.class);
        RuleBasedGuardrailService guardrails = mock(RuleBasedGuardrailService.class);

        when(loadingPrompt.build(any(GenerateClaimRequest.class))).thenReturn(List.of(
                new GigaChatMessage("system", "system"),
                new GigaChatMessage("user", "user")
        ));
        when(client.sendChatWithTrace(anyList(), anyString(), any(), anyString(), anyLong()))
                .thenReturn(new GigaChatClient.ChatCallResult("first", response("bad", 10, 5, 15)))
                .thenThrow(new ResourceAccessException("read timed out", new SocketTimeoutException("Read timed out")));

        GenerateClaimResponse bad = mock(GenerateClaimResponse.class);
        when(parser.parse("bad")).thenReturn(bad);
        GuardrailResult blocked = new GuardrailResult(
                GuardrailDecision.BLOCK,
                List.of("claim_text does not contain expected shipment.order_number: ORD-LF-200"),
                List.of()
        );
        when(guardrails.check(any(GenerateClaimRequest.class), same(bad))).thenReturn(blocked);

        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                rag, paymentPrompt, loadingPrompt, client, parser, guardrails
        );

        var result = service.generate(request());

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.guardrailResult().warnings())
                .contains("LLM repair timed out within the claim generation latency budget");
    }

    @Test
    void claimPipelineDoesNotHideNonTimeoutRepairNetworkFailure() {
        RagSearchService rag = mock(RagSearchService.class);
        PaymentDelayPromptBuilder paymentPrompt = mock(PaymentDelayPromptBuilder.class);
        LoadingFailurePromptBuilder loadingPrompt = mock(LoadingFailurePromptBuilder.class);
        GigaChatClient client = mock(GigaChatClient.class);
        ClaimResponseParser parser = mock(ClaimResponseParser.class);
        RuleBasedGuardrailService guardrails = mock(RuleBasedGuardrailService.class);

        when(loadingPrompt.build(any(GenerateClaimRequest.class))).thenReturn(List.of(
                new GigaChatMessage("system", "system"),
                new GigaChatMessage("user", "user")
        ));
        when(client.sendChatWithTrace(anyList(), anyString(), any(), anyString(), anyLong()))
                .thenReturn(new GigaChatClient.ChatCallResult("first", response("bad", 10, 5, 15)))
                .thenThrow(new ResourceAccessException("connection failed", new ConnectException("refused")));

        GenerateClaimResponse bad = mock(GenerateClaimResponse.class);
        when(parser.parse("bad")).thenReturn(bad);
        when(guardrails.check(any(GenerateClaimRequest.class), same(bad)))
                .thenReturn(new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("claim_text does not contain expected shipment.order_number: ORD-LF-200"),
                        List.of()
                ));

        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                rag, paymentPrompt, loadingPrompt, client, parser, guardrails
        );

        assertThatThrownBy(() -> service.generate(request()))
                .isInstanceOf(ResourceAccessException.class)
                .hasMessageContaining("connection failed");
    }

    @Test
    void paymentDelayRepairsContractCitationsWithoutSecondLlmCall() {
        RagSearchService rag = mock(RagSearchService.class);
        PaymentDelayPromptBuilder paymentPrompt = mock(PaymentDelayPromptBuilder.class);
        LoadingFailurePromptBuilder loadingPrompt = mock(LoadingFailurePromptBuilder.class);
        GigaChatClient client = mock(GigaChatClient.class);
        ClaimResponseParser parser = mock(ClaimResponseParser.class);
        RuleBasedGuardrailService guardrails = mock(RuleBasedGuardrailService.class);

        when(paymentPrompt.build(any(GenerateClaimRequest.class))).thenReturn(List.of(
                new GigaChatMessage("system", "system"),
                new GigaChatMessage("user", "user")
        ));
        when(client.sendChatWithTrace(anyList(), anyString(), any(), anyString(), anyLong()))
                .thenReturn(new GigaChatClient.ChatCallResult("first", response("citation-bad", 100, 20, 120)));

        GenerateClaimResponse blocked = paymentResponseMissingCanonicalCitation();
        when(parser.parse("citation-bad")).thenReturn(blocked);
        when(guardrails.check(any(GenerateClaimRequest.class), any(GenerateClaimResponse.class)))
                .thenReturn(new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of(
                                "claim_text contract clause citation must include contract number in the same sentence: 8.2",
                                "claim_text must cite a PAYMENT_TERMS contract clause in the same logical line as payment term",
                                "claim_text contract clause citation must include contract number in the same sentence: 10.2",
                                "claim_text must cite a CLAIM_PROCEDURE contract clause in the same logical line as claim response deadline"
                        ),
                        List.of()
                ))
                .thenReturn(new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of()));

        ClaimGenerationPipelineService service = new ClaimGenerationPipelineService(
                rag, paymentPrompt, loadingPrompt, client, parser, guardrails
        );

        var result = service.generate(paymentRequest());

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.generatedClaim().claimText())
                .contains("пп. 8.2 и 8.4 Договора № АН-ТЭ/2026-013")
                .contains("п. 10.2 Договора № АН-ТЭ/2026-013");
        verify(client, times(1)).sendChatWithTrace(anyList(), anyString(), any(), anyString(), anyLong());
        verify(paymentPrompt, never()).buildRepair(any(), any(), anyList());
    }

    @Test
    void documentPipelineRetriesOnceAndAggregatesUsageAfterGuardrailBlock() {
        RagSearchService rag = mock(RagSearchService.class);
        LoadingFailureNotificationPromptBuilder notificationPrompt = mock(LoadingFailureNotificationPromptBuilder.class);
        LoadingFailureActPromptBuilder actPrompt = mock(LoadingFailureActPromptBuilder.class);
        GigaChatClient client = mock(GigaChatClient.class);
        DocumentResponseParser parser = mock(DocumentResponseParser.class);
        DocumentGuardrailService guardrails = mock(DocumentGuardrailService.class);

        when(notificationPrompt.build(any(GenerateClaimRequest.class))).thenReturn(List.of(
                new GigaChatMessage("system", "system"),
                new GigaChatMessage("user", "user")
        ));

        when(client.sendChatWithTrace(anyList(), anyString(), anyString()))
                .thenReturn(new GigaChatClient.ChatCallResult("first", response("bad", 90, 10, 100)))
                .thenReturn(new GigaChatClient.ChatCallResult("second", response("good", 1, 15, 16)));

        GenerateDocumentResponse bad = mock(GenerateDocumentResponse.class);
        GenerateDocumentResponse good = mock(GenerateDocumentResponse.class);
        when(parser.parse("bad")).thenReturn(bad);
        when(parser.parse("good")).thenReturn(good);
        when(guardrails.check(any(GenerateClaimRequest.class), same(bad), eq(GenerateClaimResponse.DocumentType.NOTIFICATION)))
                .thenReturn(new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("document_text does not contain expected case_facts.claim_date: 13.06.2026"),
                        List.of()
                ));
        when(guardrails.check(any(GenerateClaimRequest.class), same(good), eq(GenerateClaimResponse.DocumentType.NOTIFICATION)))
                .thenReturn(new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of()));

        DocumentGenerationPipelineService service = new DocumentGenerationPipelineService(
                rag,
                notificationPrompt,
                actPrompt,
                client,
                parser,
                guardrails
        );

        var result = service.generateNotification(request());

        assertThat(result.success()).isEqualTo(true);
        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.requestId()).isEqualTo("second");
        assertThat(result.tokenUsage().promptTokens()).isEqualTo(91);
        assertThat(result.tokenUsage().completionTokens()).isEqualTo(25);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(116);
        verify(client, times(2)).sendChatWithTrace(anyList(), anyString(), anyString());
    }

    private GenerateClaimPipelineRequest request() {
        GenerateClaimRequest.CaseFacts facts = new GenerateClaimRequest.CaseFacts(
                "claim-lf-repair",
                GenerateClaimRequest.ClaimType.LOADING_FAILURE,
                new GenerateClaimRequest.Party("ООО Клиент-Заказчик", "7700000000", "Москва"),
                new GenerateClaimRequest.Party("ООО Перевозчик", "7800000000", "Санкт-Петербург"),
                new GenerateClaimRequest.ContractFacts("LF-77/2026", "05.02.2026"),
                new GenerateClaimRequest.ShipmentFacts(
                        "ORD-LF-200",
                        "Москва - Казань",
                        "ACT-LF-200",
                        "12.06.2026",
                        null,
                        null,
                        "12.06.2026",
                        "Москва, склад №4",
                        "09:00-12:00",
                        "тент 20 т",
                        "ООО Перевозчик",
                        true
                ),
                null,
                "13.06.2026"
        );
        GenerateClaimRequest.BackendCalculation calculation = new GenerateClaimRequest.BackendCalculation(
                BigDecimal.ZERO,
                GenerateClaimRequest.PenaltyType.CONTRACT_PENALTY,
                "фиксированный штраф",
                0,
                new BigDecimal("15000"),
                new BigDecimal("15000"),
                "RUB",
                "15000"
        );
        return new GenerateClaimPipelineRequest(
                facts,
                calculation,
                List.of(),
                List.of(),
                null,
                List.of(),
                new GenerateClaimPipelineRequest.RagOptions(false, null, null)
        );
    }

    private GenerateClaimPipelineRequest paymentRequest() {
        GenerateClaimRequest.CaseFacts facts = new GenerateClaimRequest.CaseFacts(
                "claim-payment-fast-repair",
                "CLM-FAST-1",
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                new GenerateClaimRequest.Party("ООО Маршал", "7812456730", "Санкт-Петербург"),
                new GenerateClaimRequest.Party("АО АлтайНапитки", "2224186305", "Барнаул"),
                new GenerateClaimRequest.ContractFacts(
                        "АН-ТЭ/2026-013",
                        "2026-03-31",
                        30,
                        GenerateClaimRequest.TermDayType.CALENDAR_DAYS,
                        null
                ),
                new GenerateClaimRequest.ShipmentFacts(
                        "РЕЙС-АЛТАЙ-395-01",
                        "Барнаул - Новосибирск",
                        null,
                        "2026-05-30",
                        null,
                        null
                ),
                new GenerateClaimRequest.PaymentFacts(
                        "2026-07-16",
                        GenerateClaimRequest.PaymentStatus.UNPAID,
                        true
                ),
                "2026-08-15",
                null
        );
        GenerateClaimRequest.BackendCalculation calculation = new GenerateClaimRequest.BackendCalculation(
                new BigDecimal("100000.00"),
                GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                "по периодам ключевой ставки",
                29,
                new BigDecimal("1119.18"),
                new BigDecimal("101119.18"),
                "RUB",
                "backend",
                "2026-07-17",
                "2026-08-14",
                new BigDecimal("100000.00"),
                BigDecimal.ZERO
        );
        return new GenerateClaimPipelineRequest(
                facts,
                calculation,
                List.of(
                        new GenerateClaimRequest.ContractContextChunk(
                                "pay-8-2", "8.2", "Оплата", "PAYMENT_TERMS", "45 календарных дней"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "pay-8-4", "8.4", "Платежные дни", "PAYMENT_TERMS", "вторник и четверг"
                        ),
                        new GenerateClaimRequest.ContractContextChunk(
                                "claim-10-2", "10.2", "Претензионный порядок", "CLAIM_PROCEDURE", "30 календарных дней"
                        )
                ),
                List.of(),
                null,
                List.of(),
                new GenerateClaimPipelineRequest.RagOptions(false, null, null)
        );
    }

    private GenerateClaimResponse paymentResponseMissingCanonicalCitation() {
        return new GenerateClaimResponse(
                GenerateClaimRequest.ClaimType.PAYMENT_DELAY,
                """
                Оплата должна быть произведена в течение 45 календарных дней согласно п. 8.2 и п. 8.4.
                Направить письменный ответ в течение 30 календарных дней с даты получения претензии согласно п. 10.2.
                """,
                "summary",
                List.of(
                        new GenerateClaimResponse.UsedContractClause("8.2", "pay-8-2", "срок оплаты"),
                        new GenerateClaimResponse.UsedContractClause("8.4", "pay-8-4", "платежные дни"),
                        new GenerateClaimResponse.UsedContractClause("10.2", "claim-10-2", "срок ответа")
                ),
                List.of(),
                new GenerateClaimResponse.BackendCalculationUsed(
                        new BigDecimal("100000.00"),
                        GenerateClaimRequest.PenaltyType.LEGAL_INTEREST,
                        new BigDecimal("1119.18"),
                        new BigDecimal("101119.18"),
                        29,
                        "RUB"
                ),
                List.of(),
                List.of(),
                true
        );
    }

    private GigaChatChatResponse response(String content, int prompt, int completion, int total) {
        return new GigaChatChatResponse(
                List.of(new GigaChatChatResponse.Choice(
                        0,
                        new GigaChatMessage("assistant", content)
                )),
                new GigaChatChatResponse.Usage(prompt, completion, total)
        );
    }

}

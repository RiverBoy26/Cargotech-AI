// FULL VERSION: 4 retry tests; compatible with GeneratedOutputNormalizer.
package ru.sber.cargotech.ai;

import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(client.sendChatWithTrace(anyList(), anyString(), anyString()))
                .thenReturn(firstCall)
                .thenReturn(secondCall);

        GenerateClaimResponse bad = mock(GenerateClaimResponse.class);
        GenerateClaimResponse good = mock(GenerateClaimResponse.class);
        when(parser.parse("bad")).thenReturn(bad);
        when(parser.parse("good")).thenReturn(good);
        when(guardrails.check(
                any(GenerateClaimRequest.class),
                any(GenerateClaimResponse.class)
        )).thenReturn(
                new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("claim_text does not contain expected shipment.order_number: ORD-LF-200"),
                        List.of()
                ),
                new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of())
        );

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
        verify(client, times(2)).sendChatWithTrace(anyList(), anyString(), anyString());
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
        when(guardrails.check(
                any(GenerateClaimRequest.class),
                any(GenerateDocumentResponse.class),
                eq(GenerateClaimResponse.DocumentType.NOTIFICATION)
        )).thenReturn(
                new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("document_text does not contain expected case_facts.claim_date: 13.06.2026"),
                        List.of()
                ),
                new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of())
        );

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


    @Test
    void claimPipelineRetriesOnFixableReviewWarning() {
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
        when(client.sendChatWithTrace(anyList(), anyString(), anyString()))
                .thenReturn(new GigaChatClient.ChatCallResult("first", response("review", 80, 20, 100)))
                .thenReturn(new GigaChatClient.ChatCallResult("second", response("fixed", 2, 18, 20)));

        GenerateClaimResponse review = mock(GenerateClaimResponse.class);
        GenerateClaimResponse fixed = mock(GenerateClaimResponse.class);
        when(parser.parse("review")).thenReturn(review);
        when(parser.parse("fixed")).thenReturn(fixed);
        when(guardrails.check(
                any(GenerateClaimRequest.class),
                any(GenerateClaimResponse.class)
        )).thenReturn(
                new GuardrailResult(
                        GuardrailDecision.REVIEW,
                        List.of(),
                        List.of("Use the legal term «непредоставление транспортного средства», not «непредставление»")
                ),
                new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of())
        );

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
        assertThat(result.tokenUsage().promptTokens()).isEqualTo(82);
        assertThat(result.tokenUsage().completionTokens()).isEqualTo(38);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(120);
        verify(client, times(2)).sendChatWithTrace(anyList(), anyString(), anyString());
    }

    @Test
    void documentPipelineRetriesTwiceWhenFirstRepairStillBlocked() {
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
                .thenReturn(new GigaChatClient.ChatCallResult("first", response("bad-text", 100, 20, 120)))
                .thenReturn(new GigaChatClient.ChatCallResult("second", response("bad-summary", 3, 15, 18)))
                .thenReturn(new GigaChatClient.ChatCallResult("third", response("good", 4, 16, 20)));

        GenerateDocumentResponse badText = mock(GenerateDocumentResponse.class);
        GenerateDocumentResponse badSummary = mock(GenerateDocumentResponse.class);
        GenerateDocumentResponse good = mock(GenerateDocumentResponse.class);
        when(parser.parse("bad-text")).thenReturn(badText);
        when(parser.parse("bad-summary")).thenReturn(badSummary);
        when(parser.parse("good")).thenReturn(good);

        when(guardrails.check(
                any(GenerateClaimRequest.class),
                any(GenerateDocumentResponse.class),
                eq(GenerateClaimResponse.DocumentType.NOTIFICATION)
        )).thenReturn(
                new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("document_text does not contain expected case_facts.claim_date: 13.06.2026"),
                        List.of()
                ),
                new GuardrailResult(
                        GuardrailDecision.BLOCK,
                        List.of("Document replaces confirmed vehicle non-provision with absence of confirmation"),
                        List.of()
                ),
                new GuardrailResult(GuardrailDecision.PASS, List.of(), List.of())
        );

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
        assertThat(result.requestId()).isEqualTo("third");
        assertThat(result.tokenUsage().promptTokens()).isEqualTo(107);
        assertThat(result.tokenUsage().completionTokens()).isEqualTo(51);
        assertThat(result.tokenUsage().totalTokens()).isEqualTo(158);
        verify(client, times(3)).sendChatWithTrace(anyList(), anyString(), anyString());
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

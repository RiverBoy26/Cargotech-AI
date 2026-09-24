package ru.sber.cargotech.ai.llm.logging;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.security.SensitiveDataMasker;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmUsageLimitAccountingTest {

    @Test
    void localFailuresDoNotConsumeProviderCallQuotaButProviderAttemptsDo() {
        LlmLogService service = new LlmLogService(new SensitiveDataMasker());
        ReflectionTestUtils.setField(service, "maxCallsPerClaim", 1);

        String claimId = "claim-limit-test";
        UUID userId = UUID.fromString("f1d15888-d170-493d-a463-51ceaf64c6a3");
        List<GigaChatMessage> messages = List.of(new GigaChatMessage("user", "test"));

        service.logError(
                "local-error",
                claimId,
                userId,
                "GENERATE_CLAIM_PAYMENT_DELAY",
                "GigaChat",
                "GigaChat-2-Pro",
                messages,
                null,
                new AiUsageLimitExceededException("Достигнут лимит AI"),
                Instant.now(),
                false
        );

        assertThatCode(() -> service.ensureWithinLimits(claimId)).doesNotThrowAnyException();

        service.logError(
                "provider-error",
                claimId,
                userId,
                "GENERATE_CLAIM_PAYMENT_DELAY",
                "GigaChat",
                "GigaChat-2-Pro",
                messages,
                null,
                new IllegalStateException("422 Unprocessable Content"),
                Instant.now(),
                true
        );

        assertThatThrownBy(() -> service.ensureWithinLimits(claimId))
                .isInstanceOf(AiUsageLimitExceededException.class)
                .hasMessageContaining("не более 1 вызовов");
    }

    @Test
    void postResponseFailureStillAccountsProviderTokensAndCost() {
        LlmLogService service = new LlmLogService(new SensitiveDataMasker());
        UUID userId = UUID.randomUUID();
        GigaChatChatResponse providerResponse = new GigaChatChatResponse(
                List.of(new GigaChatChatResponse.Choice(
                        0,
                        new GigaChatMessage("assistant", "Ответ __CTP_999__")
                )),
                new GigaChatChatResponse.Usage(1000, 500, 1500)
        );

        service.logError(
                "post-response-error",
                "claim-cost-test",
                userId,
                "GENERATE_CLAIM_PAYMENT_DELAY",
                "GigaChat",
                "GigaChat-2-Pro",
                List.of(new GigaChatMessage("user", "test")),
                providerResponse,
                new IllegalStateException("LLM response contains unresolved sensitive-data placeholder: __CTP_999__"),
                Instant.now(),
                true
        );

        LlmCallLog entry = service.recentLogs().getFirst();
        org.assertj.core.api.Assertions.assertThat(entry.providerInvoked()).isTrue();
        org.assertj.core.api.Assertions.assertThat(entry.promptTokens()).isEqualTo(1000);
        org.assertj.core.api.Assertions.assertThat(entry.completionTokens()).isEqualTo(500);
        org.assertj.core.api.Assertions.assertThat(entry.totalTokens()).isEqualTo(1500);
        org.assertj.core.api.Assertions.assertThat(entry.costRub()).isEqualByComparingTo(new BigDecimal("0.3000"));
        org.assertj.core.api.Assertions.assertThat(entry.rawResponse()).contains("__CTP_999__");
    }

}

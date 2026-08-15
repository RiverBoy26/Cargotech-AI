package ru.sber.cargotech.ai.gigachat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatRequest;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.llm.logging.LlmLogService;
import ru.sber.cargotech.ai.security.ReversiblePromptMasker;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GigaChatClient {

    private final GigaChatProperties properties;
    private final GigaChatAuthService authService;
    private final RestClient restClient;
    private final RestClient.Builder restClientBuilder;
    private final LlmLogService llmLogService;
    private final ReversiblePromptMasker reversiblePromptMasker;

    public GigaChatClient(
            GigaChatProperties properties,
            GigaChatAuthService authService,
            RestClient.Builder restClientBuilder,
            LlmLogService llmLogService,
            ReversiblePromptMasker reversiblePromptMasker
    ) {
        this.properties = properties;
        this.authService = authService;
        this.restClientBuilder = restClientBuilder.clone();
        this.restClient = this.restClientBuilder.build();
        this.llmLogService = llmLogService;
        this.reversiblePromptMasker = reversiblePromptMasker;
    }

    public GigaChatChatResponse sendChat(List<GigaChatMessage> messages) {
        return sendChat(messages, null, "GIGACHAT_CHAT_TEST");
    }

    public GigaChatChatResponse sendChat(List<GigaChatMessage> messages, String caseId, String operation) {
        return sendChatWithTrace(messages, caseId, operation).response();
    }

    public ChatCallResult sendChatWithTrace(List<GigaChatMessage> messages, String caseId, String operation) {
        return sendChatWithTrace(messages, caseId, null, operation);
    }

    public ChatCallResult sendChatWithTrace(
            List<GigaChatMessage> messages,
            String caseId,
            UUID userId,
            String operation
    ) {
        return sendChatWithTrace(messages, caseId, userId, operation, null);
    }

    public ChatCallResult sendChatWithTrace(
            List<GigaChatMessage> messages,
            String caseId,
            UUID userId,
            String operation,
            Long readTimeoutMillis
    ) {
        String requestId = llmLogService.newRequestId();
        Instant startedAt = llmLogService.now();
        boolean providerInvoked = false;
        GigaChatChatResponse providerResponse = null;

        try {
            llmLogService.ensureWithinLimits(caseId);
            String accessToken = authService.getAccessToken();

            ReversiblePromptMasker.MaskedPrompt maskedPrompt = reversiblePromptMasker.mask(messages);

            GigaChatChatRequest request = new GigaChatChatRequest(
                    properties.getChatModel(),
                    maskedPrompt.providerMessages(),
                    properties.getTemperature(),
                    properties.getMaxTokens()
            );

            providerInvoked = true;
            RestClient callClient = readTimeoutMillis == null
                    ? restClient
                    : restClientWithReadTimeout(readTimeoutMillis);
            providerResponse = callClient.post()
                    .uri(properties.getChatUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GigaChatChatResponse.class);

            if (providerResponse == null) {
                throw new IllegalStateException("GigaChat returned empty response");
            }

            GigaChatChatResponse response = maskedPrompt.restore(providerResponse);

            llmLogService.logSuccess(
                    requestId,
                    caseId,
                    userId,
                    operation,
                    "GigaChat",
                    properties.getChatModel(),
                    messages,
                    response,
                    startedAt
            );

            return new ChatCallResult(requestId, response);
        } catch (Exception e) {
            llmLogService.logError(
                    requestId,
                    caseId,
                    userId,
                    operation,
                    "GigaChat",
                    properties.getChatModel(),
                    messages,
                    providerResponse,
                    e,
                    startedAt,
                    providerInvoked
            );

            throw e;
        }
    }


    private RestClient restClientWithReadTimeout(long readTimeoutMillis) {
        if (readTimeoutMillis <= 0) {
            throw new IllegalArgumentException("readTimeoutMillis must be positive");
        }

        long timeout = Math.min(readTimeoutMillis, Integer.MAX_VALUE);
        Duration readTimeout = Duration.ofMillis(timeout);
        Duration connectTimeout = Duration.ofMillis(Math.min(timeout, 10_000L));

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return restClientBuilder.clone()
                .requestFactory(requestFactory)
                .build();
    }

    public record ChatCallResult(
            String requestId,
            GigaChatChatResponse response
    ) {
    }

    public String sendSimpleMessage(String userMessage) {
        GigaChatChatResponse response = sendChat(List.of(
                new GigaChatMessage("system", "Ты отвечаешь кратко и строго по запросу."),
                new GigaChatMessage("user", userMessage)
        ));

        String content = response.firstContent();

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("GigaChat returned empty content");
        }

        return content;
    }
}

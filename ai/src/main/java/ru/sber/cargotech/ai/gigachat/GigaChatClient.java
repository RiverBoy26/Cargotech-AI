package ru.sber.cargotech.ai.gigachat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatRequest;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.llm.logging.LlmLogService;

import java.time.Instant;
import java.util.List;

@Service
public class GigaChatClient {

    private final GigaChatProperties properties;
    private final GigaChatAuthService authService;
    private final RestClient restClient;
    private final LlmLogService llmLogService;

    public GigaChatClient(
            GigaChatProperties properties,
            GigaChatAuthService authService,
            RestClient.Builder restClientBuilder,
            LlmLogService llmLogService
    ) {
        this.properties = properties;
        this.authService = authService;
        this.restClient = restClientBuilder.build();
        this.llmLogService = llmLogService;
    }

    public GigaChatChatResponse sendChat(List<GigaChatMessage> messages) {
        return sendChat(messages, null, "GIGACHAT_CHAT_TEST");
    }

    public GigaChatChatResponse sendChat(List<GigaChatMessage> messages, String caseId, String operation) {
        return sendChatWithTrace(messages, caseId, operation).response();
    }

    public ChatCallResult sendChatWithTrace(List<GigaChatMessage> messages, String caseId, String operation) {
        String requestId = llmLogService.newRequestId();
        Instant startedAt = llmLogService.now();

        try {
            llmLogService.ensureWithinLimits(caseId);
            String accessToken = authService.getAccessToken();

            GigaChatChatRequest request = new GigaChatChatRequest(
                    properties.getChatModel(),
                    messages,
                    properties.getTemperature(),
                    properties.getMaxTokens()
            );

            GigaChatChatResponse response = restClient.post()
                    .uri(properties.getChatUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GigaChatChatResponse.class);

            if (response == null) {
                throw new IllegalStateException("GigaChat returned empty response");
            }

            llmLogService.logSuccess(
                    requestId,
                    caseId,
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
                    operation,
                    "GigaChat",
                    properties.getChatModel(),
                    messages,
                    e,
                    startedAt
            );

            throw e;
        }
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

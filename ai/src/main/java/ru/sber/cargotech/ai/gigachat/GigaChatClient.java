package ru.sber.cargotech.ai.gigachat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatRequest;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.util.List;

@Service
public class GigaChatClient {

    private final GigaChatProperties properties;
    private final GigaChatAuthService authService;
    private final RestClient restClient;

    public GigaChatClient(
            GigaChatProperties properties,
            GigaChatAuthService authService,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.authService = authService;
        this.restClient = restClientBuilder.build();
    }

    public GigaChatChatResponse sendChat(List<GigaChatMessage> messages) {
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

        return response;
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
package ru.sber.cargotech.ai.gigachat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatEmbeddingsRequest;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatEmbeddingsResponse;

import java.util.List;

@Service
public class GigaChatEmbeddingClient {

    private final GigaChatProperties properties;
    private final GigaChatAuthService authService;
    private final RestClient restClient;

    public GigaChatEmbeddingClient(
            GigaChatProperties properties,
            GigaChatAuthService authService,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.authService = authService;
        this.restClient = restClientBuilder.build();
    }

    public GigaChatEmbeddingsResponse embed(List<String> texts) {
        validateTexts(texts);

        String accessToken = authService.getAccessToken();

        GigaChatEmbeddingsRequest request = new GigaChatEmbeddingsRequest(
                properties.getEmbeddingsModel(),
                texts
        );

        GigaChatEmbeddingsResponse response = restClient.post()
                .uri(properties.getEmbeddingsUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(GigaChatEmbeddingsResponse.class);

        if (response == null) {
            throw new IllegalStateException("GigaChat embeddings returned empty response");
        }

        if (response.firstEmbedding().isEmpty()) {
            throw new IllegalStateException("GigaChat embeddings returned empty vector");
        }

        return response;
    }

    public List<Double> embedOne(String text) {
        return embed(List.of(text)).firstEmbedding();
    }

    private void validateTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embeddings input texts are empty");
        }

        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Embeddings input contains blank text");
            }
        }
    }
}
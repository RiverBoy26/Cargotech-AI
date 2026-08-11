package ru.sber.cargotech.ai.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.api.dto.EmbeddingTestRequest;
import ru.sber.cargotech.ai.config.GigaChatProperties;
import ru.sber.cargotech.ai.gigachat.GigaChatEmbeddingClient;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatEmbeddingsResponse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "ai.diagnostics", name = "endpoints-enabled", havingValue = "true")
public class GigaChatEmbeddingController {

    private final GigaChatEmbeddingClient embeddingClient;
    private final GigaChatProperties properties;

    public GigaChatEmbeddingController(
            GigaChatEmbeddingClient embeddingClient,
            GigaChatProperties properties
    ) {
        this.embeddingClient = embeddingClient;
        this.properties = properties;
    }

    @PostMapping("/api/ai/gigachat/embeddings/test")
    public Map<String, Object> testEmbedding(@RequestBody(required = false) EmbeddingTestRequest request) {
        String text = request != null && request.text() != null && !request.text().isBlank()
                ? request.text()
                : "payment delay claim";

        GigaChatEmbeddingsResponse response = embeddingClient.embed(List.of(text));
        List<Double> vector = response.firstEmbedding();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("configured_model", properties.getEmbeddingsModel());
        result.put("response_model", response.model());
        result.put("input", text);
        result.put("dimension", vector.size());
        result.put("embedding_preview", vector.stream().limit(8).toList());
        result.put("usage", response.usage());
        result.put("checkedAt", Instant.now().toString());

        return result;
    }
}
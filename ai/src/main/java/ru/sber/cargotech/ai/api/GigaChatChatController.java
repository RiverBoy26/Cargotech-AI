package ru.sber.cargotech.ai.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.api.dto.TestChatRequest;
import ru.sber.cargotech.ai.gigachat.GigaChatClient;

import java.time.Instant;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "ai.diagnostics", name = "endpoints-enabled", havingValue = "true")
public class GigaChatChatController {

    private final GigaChatClient gigaChatClient;

    public GigaChatChatController(GigaChatClient gigaChatClient) {
        this.gigaChatClient = gigaChatClient;
    }

    @PostMapping("/api/ai/gigachat/chat/test")
    public Map<String, Object> testChat(@RequestBody(required = false) TestChatRequest request) {
        String message = request != null && request.message() != null && !request.message().isBlank()
                ? request.message()
                : "Ответь одним словом: OK";

        String answer = gigaChatClient.sendSimpleMessage(message);

        return Map.of(
                "success", true,
                "request", message,
                "answer", answer,
                "checkedAt", Instant.now().toString()
        );
    }
}
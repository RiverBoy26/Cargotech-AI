package ru.sber.cargotech.ai.document.api;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.document.prompt.LoadingFailureActPromptBuilder;
import ru.sber.cargotech.ai.document.prompt.LoadingFailureNotificationPromptBuilder;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/documents/prompt/loading-failure")
public class LoadingFailureDocumentPromptPreviewController {

    private final LoadingFailureNotificationPromptBuilder notificationPromptBuilder;
    private final LoadingFailureActPromptBuilder actPromptBuilder;

    public LoadingFailureDocumentPromptPreviewController(
            LoadingFailureNotificationPromptBuilder notificationPromptBuilder,
            LoadingFailureActPromptBuilder actPromptBuilder
    ) {
        this.notificationPromptBuilder = notificationPromptBuilder;
        this.actPromptBuilder = actPromptBuilder;
    }

    @PostMapping("/notification/preview")
    public Map<String, Object> previewNotification(@RequestBody GenerateClaimRequest request) {
        List<GigaChatMessage> messages = notificationPromptBuilder.build(request);

        return Map.of(
                "success", true,
                "document_type", "NOTIFICATION",
                "message_count", messages.size(),
                "messages", messages,
                "checkedAt", Instant.now().toString()
        );
    }

    @PostMapping("/act/preview")
    public Map<String, Object> previewAct(@RequestBody GenerateClaimRequest request) {
        List<GigaChatMessage> messages = actPromptBuilder.build(request);

        return Map.of(
                "success", true,
                "document_type", "LOADING_FAILURE_ACT",
                "message_count", messages.size(),
                "messages", messages,
                "checkedAt", Instant.now().toString()
        );
    }
}
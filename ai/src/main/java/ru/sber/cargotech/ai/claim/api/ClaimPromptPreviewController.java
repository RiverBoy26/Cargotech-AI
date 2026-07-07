package ru.sber.cargotech.ai.claim.api;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.prompt.PaymentDelayPromptBuilder;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/claims")
public class ClaimPromptPreviewController {

    private final PaymentDelayPromptBuilder paymentDelayPromptBuilder;

    public ClaimPromptPreviewController(PaymentDelayPromptBuilder paymentDelayPromptBuilder) {
        this.paymentDelayPromptBuilder = paymentDelayPromptBuilder;
    }

    @PostMapping("/prompt/payment-delay/preview")
    public Map<String, Object> previewPaymentDelayPrompt(@RequestBody GenerateClaimRequest request) {
        List<GigaChatMessage> messages = paymentDelayPromptBuilder.build(request);

        return Map.of(
                "success", true,
                "message_count", messages.size(),
                "messages", messages,
                "checkedAt", Instant.now().toString()
        );
    }
}
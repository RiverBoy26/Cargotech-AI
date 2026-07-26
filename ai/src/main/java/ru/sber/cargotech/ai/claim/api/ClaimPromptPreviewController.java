package ru.sber.cargotech.ai.claim.api;

import org.springframework.web.bind.annotation.*;
import ru.sber.cargotech.ai.claim.dto.GenerateClaimRequest;
import ru.sber.cargotech.ai.claim.prompt.LoadingFailurePromptBuilder;
import ru.sber.cargotech.ai.claim.prompt.PaymentDelayPromptBuilder;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/claims")
public class ClaimPromptPreviewController {

    private final PaymentDelayPromptBuilder paymentDelayPromptBuilder;
    private final LoadingFailurePromptBuilder loadingFailurePromptBuilder;

    public ClaimPromptPreviewController(
            PaymentDelayPromptBuilder paymentDelayPromptBuilder,
            LoadingFailurePromptBuilder loadingFailurePromptBuilder
    ) {
        this.paymentDelayPromptBuilder = paymentDelayPromptBuilder;
        this.loadingFailurePromptBuilder = loadingFailurePromptBuilder;
    }

    @PostMapping("/prompt/payment-delay/preview")
    public Map<String, Object> previewPaymentDelayPrompt(@RequestBody GenerateClaimRequest request) {
        List<GigaChatMessage> messages = paymentDelayPromptBuilder.build(request);

        return Map.of(
                "success", true,
                "claim_type", "PAYMENT_DELAY",
                "message_count", messages.size(),
                "messages", messages,
                "checkedAt", Instant.now().toString()
        );
    }

    @PostMapping("/prompt/loading-failure/preview")
    public Map<String, Object> previewLoadingFailurePrompt(@RequestBody GenerateClaimRequest request) {
        List<GigaChatMessage> messages = loadingFailurePromptBuilder.build(request);

        return Map.of(
                "success", true,
                "claim_type", "LOADING_FAILURE",
                "message_count", messages.size(),
                "messages", messages,
                "checkedAt", Instant.now().toString()
        );
    }
}

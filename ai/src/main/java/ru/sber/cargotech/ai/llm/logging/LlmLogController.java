package ru.sber.cargotech.ai.llm.logging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@ConditionalOnProperty(prefix = "ai.diagnostics", name = "endpoints-enabled", havingValue = "true")
public class LlmLogController {

    private final LlmLogService llmLogService;

    public LlmLogController(LlmLogService llmLogService) {
        this.llmLogService = llmLogService;
    }

    @GetMapping("/api/ai/llm/logs/recent")
    public Map<String, Object> recentLogs() {
        return Map.of(
                "success", true,
                "logs", llmLogService.recentLogs(),
                "checkedAt", Instant.now().toString()
        );
    }
}
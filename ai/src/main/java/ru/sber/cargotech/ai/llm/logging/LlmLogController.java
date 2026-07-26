package ru.sber.cargotech.ai.llm.logging;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
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
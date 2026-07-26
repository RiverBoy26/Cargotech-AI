package ru.sber.cargotech.ai.llm.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LlmLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmLogService.class);
    private static final int MAX_IN_MEMORY_LOGS = 100;

    private final ArrayDeque<LlmCallLog> logs = new ArrayDeque<>();

    public String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public Instant now() {
        return Instant.now();
    }

    public void logSuccess(
            String requestId,
            String caseId,
            String operation,
            String provider,
            String model,
            List<GigaChatMessage> messages,
            GigaChatChatResponse response,
            Instant startedAt
    ) {
        Instant finishedAt = Instant.now();

        GigaChatChatResponse.Usage usage = response == null ? null : response.usage();

        LlmCallLog entry = new LlmCallLog(
                requestId,
                caseId,
                operation,
                provider,
                model,
                previewMessages(messages),
                response == null ? null : preview(response.firstContent()),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                LlmCallStatus.SUCCESS,
                null,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis()
        );

        save(entry);

        log.info(
                "LLM call success: requestId={}, caseId={}, operation={}, provider={}, model={}, durationMs={}, totalTokens={}",
                requestId,
                caseId,
                operation,
                provider,
                model,
                entry.durationMs(),
                entry.totalTokens()
        );
    }

    public void logError(
            String requestId,
            String caseId,
            String operation,
            String provider,
            String model,
            List<GigaChatMessage> messages,
            Exception exception,
            Instant startedAt
    ) {
        Instant finishedAt = Instant.now();

        LlmCallLog entry = new LlmCallLog(
                requestId,
                caseId,
                operation,
                provider,
                model,
                previewMessages(messages),
                null,
                null,
                null,
                null,
                LlmCallStatus.ERROR,
                exception == null ? "Unknown error" : preview(exception.getMessage()),
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis()
        );

        save(entry);

        log.error(
                "LLM call error: requestId={}, caseId={}, operation={}, provider={}, model={}, durationMs={}, error={}",
                requestId,
                caseId,
                operation,
                provider,
                model,
                entry.durationMs(),
                entry.errorMessage()
        );
    }

    public synchronized List<LlmCallLog> recentLogs() {
        return new ArrayList<>(logs);
    }

    private synchronized void save(LlmCallLog entry) {
        logs.addFirst(entry);

        while (logs.size() > MAX_IN_MEMORY_LOGS) {
            logs.removeLast();
        }
    }

    private String previewMessages(List<GigaChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        for (GigaChatMessage message : messages) {
            if (message == null) {
                continue;
            }

            builder
                    .append("[")
                    .append(message.role())
                    .append("] ")
                    .append(message.content())
                    .append("\n\n");
        }

        return preview(builder.toString());
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = value
                .replaceAll("(?i)authorization:\\s*basic\\s+[a-z0-9+/=._-]+", "Authorization: Basic ***")
                .replaceAll("(?i)authorization:\\s*bearer\\s+[a-z0-9+/=._-]+", "Authorization: Bearer ***")
                .trim();

        int limit = 4000;

        if (cleaned.length() <= limit) {
            return cleaned;
        }

        return cleaned.substring(0, limit) + "...[truncated]";
    }
}
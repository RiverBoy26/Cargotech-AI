package ru.sber.cargotech.ai.llm.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatChatResponse;
import ru.sber.cargotech.ai.gigachat.dto.GigaChatMessage;
import ru.sber.cargotech.ai.security.SensitiveDataMasker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LlmLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmLogService.class);
    private static final int MAX_IN_MEMORY_LOGS = 100;

    private final ArrayDeque<LlmCallLog> logs = new ArrayDeque<>();
    private final SensitiveDataMasker sensitiveDataMasker;
    private final JdbcTemplate jdbcTemplate;

    @Value("${ai.limits.max-calls-per-claim:8}")
    private int maxCallsPerClaim = 8;
    @Value("${ai.limits.max-cost-per-claim-rub:50}")
    private BigDecimal maxCostPerClaimRub = new BigDecimal("50");
    @Value("${ai.limits.input-token-cost-rub-per-1000:0.20}")
    private BigDecimal inputTokenCostRubPer1000 = new BigDecimal("0.20");
    @Value("${ai.limits.output-token-cost-rub-per-1000:0.20}")
    private BigDecimal outputTokenCostRubPer1000 = new BigDecimal("0.20");

    @Autowired
    public LlmLogService(
            SensitiveDataMasker sensitiveDataMasker,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider
    ) {
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    }

    /** Compatibility constructor for isolated unit tests. */
    public LlmLogService(SensitiveDataMasker sensitiveDataMasker) {
        this.sensitiveDataMasker = sensitiveDataMasker;
        this.jdbcTemplate = null;
    }

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
        String rawPrompt = rawMessages(messages);
        String rawResponse = response == null ? null : response.firstContent();

        LlmCallLog entry = new LlmCallLog(
                requestId,
                caseId,
                operation,
                provider,
                model,
                promptVersion(operation),
                rawPrompt,
                preview(rawPrompt),
                rawResponse,
                preview(rawResponse),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens(),
                usage == null ? null : usage.totalTokens(),
                calculateCost(usage),
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
                promptVersion(operation),
                rawMessages(messages),
                previewMessages(messages),
                null,
                null,
                null,
                null,
                null,
                BigDecimal.ZERO.setScale(4),
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
                entry.errorMessage() == null ? "unknown" : "masked"
        );
    }

    /** Enforces per-claim limits before the provider receives another request. */
    public synchronized void ensureWithinLimits(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            return;
        }
        UsageTotals usage = loadUsage(caseId);
        if (usage.calls() >= maxCallsPerClaim) {
            throw new AiUsageLimitExceededException(
                    "Достигнут лимит AI для претензии: не более " + maxCallsPerClaim + " вызовов"
            );
        }
        if (usage.costRub().compareTo(maxCostPerClaimRub) >= 0) {
            throw new AiUsageLimitExceededException(
                    "Достигнут лимит стоимости AI для претензии: не более "
                            + maxCostPerClaimRub.stripTrailingZeros().toPlainString() + " ₽"
            );
        }
    }

    public synchronized List<LlmCallLog> recentLogs() {
        return new ArrayList<>(logs);
    }

    private synchronized void save(LlmCallLog entry) {
        logs.addFirst(entry);

        while (logs.size() > MAX_IN_MEMORY_LOGS) {
            logs.removeLast();
        }

        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update("""
                        INSERT INTO cargotech.ai_llm_call_logs (
                            request_id, claim_id, operation, provider, model, prompt_version,
                            raw_prompt, masked_prompt, raw_response, masked_response,
                            prompt_tokens, completion_tokens, total_tokens, cost_rub,
                            status, error_message, started_at, finished_at, duration_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        entry.requestId(), entry.caseId(), entry.operation(), entry.provider(), entry.model(),
                        entry.promptVersion(), entry.rawPrompt(), entry.maskedPrompt(), entry.rawResponse(),
                        entry.maskedResponse(), entry.promptTokens(), entry.completionTokens(), entry.totalTokens(),
                        entry.costRub(), entry.status().name(), entry.errorMessage(),
                        entry.startedAt() == null ? null : entry.startedAt().atOffset(ZoneOffset.UTC),
                        entry.finishedAt() == null ? null : entry.finishedAt().atOffset(ZoneOffset.UTC),
                        entry.durationMs()
                );
            } catch (RuntimeException persistenceError) {
                SQLException sqlException = findSqlException(persistenceError);
                log.error(
                        "Failed to persist LLM audit metadata: requestId={}, exceptionType={}, sqlState={}, vendorCode={}",
                        entry.requestId(),
                        persistenceError.getClass().getSimpleName(),
                        sqlException == null ? "n/a" : sqlException.getSQLState(),
                        sqlException == null ? 0 : sqlException.getErrorCode()
                );
            }
        }
    }

    private UsageTotals loadUsage(String caseId) {
        if (jdbcTemplate != null) {
            try {
                return jdbcTemplate.queryForObject("""
                        SELECT COUNT(*), COALESCE(SUM(cost_rub), 0)
                        FROM cargotech.ai_llm_call_logs WHERE claim_id = ?
                        """, (rs, rowNum) -> new UsageTotals(rs.getInt(1), rs.getBigDecimal(2)), caseId);
            } catch (RuntimeException persistenceError) {
                SQLException sqlException = findSqlException(persistenceError);
                log.warn(
                        "Using in-memory AI limits: claimId={}, exceptionType={}, sqlState={}, vendorCode={}",
                        caseId,
                        persistenceError.getClass().getSimpleName(),
                        sqlException == null ? "n/a" : sqlException.getSQLState(),
                        sqlException == null ? 0 : sqlException.getErrorCode()
                );
            }
        }
        int calls = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (LlmCallLog entry : logs) {
            if (caseId.equals(entry.caseId())) {
                calls++;
                cost = cost.add(entry.costRub() == null ? BigDecimal.ZERO : entry.costRub());
            }
        }
        return new UsageTotals(calls, cost);
    }

    private SQLException findSqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    private BigDecimal calculateCost(GigaChatChatResponse.Usage usage) {
        if (usage == null) {
            return BigDecimal.ZERO.setScale(4);
        }
        BigDecimal input = BigDecimal.valueOf(usage.promptTokens() == null ? 0 : usage.promptTokens())
                .multiply(inputTokenCostRubPer1000);
        BigDecimal output = BigDecimal.valueOf(usage.completionTokens() == null ? 0 : usage.completionTokens())
                .multiply(outputTokenCostRubPer1000);
        return input.add(output).divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
    }

    private String promptVersion(String operation) {
        return (operation == null || operation.isBlank() ? "generic" : operation.toLowerCase()) + "-v1";
    }

    private String previewMessages(List<GigaChatMessage> messages) {
        return preview(rawMessages(messages));
    }

    private String rawMessages(List<GigaChatMessage> messages) {
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

        return builder.toString().trim();
    }

    private String preview(String value) {
        if (value == null) {
            return null;
        }

        String cleaned = sensitiveDataMasker.mask(value).trim();

        int limit = 4000;

        if (cleaned.length() <= limit) {
            return cleaned;
        }

        return cleaned.substring(0, limit) + "...[truncated]";
    }

    private record UsageTotals(int calls, BigDecimal costRub) {}
}

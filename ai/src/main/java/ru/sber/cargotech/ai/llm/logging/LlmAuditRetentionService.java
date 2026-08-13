package ru.sber.cargotech.ai.llm.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps LLM audit data for the configured retention window (1 year by default). */
@Service
public class LlmAuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(LlmAuditRetentionService.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${ai.audit.retention-days:365}")
    private int retentionDays;

    public LlmAuditRetentionService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    }

    @Scheduled(cron = "${ai.audit.retention-cron:0 30 3 * * *}")
    public void purgeExpiredAuditLogs() {
        if (jdbcTemplate == null) {
            return;
        }
        if (retentionDays < 1) {
            log.warn("LLM audit retention purge skipped: invalid retentionDays={}", retentionDays);
            return;
        }

        int rawDeleted = jdbcTemplate.update(
                "DELETE FROM cargotech.ai_llm_call_log_raw WHERE created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')",
                retentionDays
        );
        int maskedDeleted = jdbcTemplate.update(
                "DELETE FROM cargotech.ai_llm_call_logs WHERE created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')",
                retentionDays
        );

        if (rawDeleted > 0 || maskedDeleted > 0) {
            log.info(
                    "LLM audit retention purge completed: retentionDays={}, rawDeleted={}, maskedDeleted={}",
                    retentionDays,
                    rawDeleted,
                    maskedDeleted
            );
        }
    }
}

-- Apply explicitly for an existing PostgreSQL volume before deploying the
-- updated AI service. Docker entrypoint init scripts only run for a new volume.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

CREATE TABLE IF NOT EXISTS cargotech.ai_llm_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(128) NOT NULL UNIQUE,
    claim_id VARCHAR(128),
    operation VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128),
    prompt_version VARCHAR(128) NOT NULL,
    raw_prompt TEXT,
    masked_prompt TEXT,
    raw_response TEXT,
    masked_response TEXT,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    cost_rub NUMERIC(14,4) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_llm_logs_claim_created
    ON cargotech.ai_llm_call_logs (claim_id, created_at DESC);

COMMIT;

-- Idempotent reconciliation for existing PostgreSQL volumes where the AI audit
-- table is missing or has an older/incomplete shape.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

CREATE TABLE IF NOT EXISTS cargotech.ai_llm_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid()
);

ALTER TABLE cargotech.ai_llm_call_logs
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS claim_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS operation VARCHAR(128),
    ADD COLUMN IF NOT EXISTS provider VARCHAR(64),
    ADD COLUMN IF NOT EXISTS model VARCHAR(128),
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(128),
    ADD COLUMN IF NOT EXISTS raw_prompt TEXT,
    ADD COLUMN IF NOT EXISTS masked_prompt TEXT,
    ADD COLUMN IF NOT EXISTS raw_response TEXT,
    ADD COLUMN IF NOT EXISTS masked_response TEXT,
    ADD COLUMN IF NOT EXISTS prompt_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS completion_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS total_tokens INTEGER,
    ADD COLUMN IF NOT EXISTS cost_rub NUMERIC(14,4) DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status VARCHAR(32),
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE cargotech.ai_llm_call_logs
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN cost_rub SET DEFAULT 0,
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_llm_logs_request_id
    ON cargotech.ai_llm_call_logs (request_id)
    WHERE request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_llm_logs_claim_created
    ON cargotech.ai_llm_call_logs (claim_id, created_at DESC);

COMMIT;

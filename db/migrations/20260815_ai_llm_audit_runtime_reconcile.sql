-- Bring existing/partially migrated AI audit tables to the schema used by LlmLogService.
-- Idempotent: safe to run repeatedly on existing test/prod volumes.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

CREATE TABLE IF NOT EXISTS cargotech.ai_llm_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid()
);

ALTER TABLE cargotech.ai_llm_call_logs
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS claim_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS user_id UUID,
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
    ADD COLUMN IF NOT EXISTS provider_invoked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS finished_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE cargotech.ai_llm_call_logs
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ALTER COLUMN cost_rub SET DEFAULT 0,
    ALTER COLUMN provider_invoked SET DEFAULT FALSE,
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

-- Old successful/provider-level attempts predate provider_invoked. Backfill only
-- states that prove the provider endpoint was actually attempted.
UPDATE cargotech.ai_llm_call_logs
SET provider_invoked = TRUE
WHERE provider_invoked = FALSE
  AND (
       status = 'SUCCESS'
       OR COALESCE(error_message, '') ~ '^[45][0-9]{2}[[:space:]]'
       OR COALESCE(error_message, '') LIKE 'LLM response contains unresolved sensitive-data placeholder:%'
       OR COALESCE(error_message, '') = 'GigaChat returned empty response'
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_llm_logs_request_id
    ON cargotech.ai_llm_call_logs (request_id)
    WHERE request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_llm_logs_claim_created
    ON cargotech.ai_llm_call_logs (claim_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_llm_claim_provider_invoked
    ON cargotech.ai_llm_call_logs (claim_id, provider_invoked);

CREATE INDEX IF NOT EXISTS idx_ai_llm_user_created
    ON cargotech.ai_llm_call_logs (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS cargotech.ai_llm_call_log_raw (
    request_id VARCHAR(128) PRIMARY KEY,
    raw_prompt TEXT,
    raw_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Preserve raw content left by the legacy single-table schema before clearing it
-- from the diagnostics-safe table.
INSERT INTO cargotech.ai_llm_call_log_raw (request_id, raw_prompt, raw_response, created_at)
SELECT request_id, raw_prompt, raw_response, COALESCE(created_at, CURRENT_TIMESTAMP)
FROM cargotech.ai_llm_call_logs
WHERE request_id IS NOT NULL
  AND (raw_prompt IS NOT NULL OR raw_response IS NOT NULL)
ON CONFLICT (request_id) DO UPDATE SET
    raw_prompt = EXCLUDED.raw_prompt,
    raw_response = EXCLUDED.raw_response;

UPDATE cargotech.ai_llm_call_logs
SET raw_prompt = NULL,
    raw_response = NULL
WHERE raw_prompt IS NOT NULL OR raw_response IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_llm_raw_created
    ON cargotech.ai_llm_call_log_raw (created_at);

REVOKE ALL ON TABLE cargotech.ai_llm_call_log_raw FROM PUBLIC;

COMMENT ON COLUMN cargotech.ai_llm_call_logs.user_id IS
    'User who initiated the claim AI generation, propagated by claim-service';

COMMENT ON COLUMN cargotech.ai_llm_call_logs.provider_invoked IS
    'TRUE only when the GigaChat chat endpoint was actually attempted';

COMMIT;

CREATE TABLE IF NOT EXISTS cargotech.ai_llm_call_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id VARCHAR(128) NOT NULL UNIQUE,
    claim_id VARCHAR(128),
    user_id UUID,
    operation VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128),
    prompt_version VARCHAR(128) NOT NULL,
    -- Legacy raw columns stay for schema compatibility but application code stores
    -- raw content only in ai_llm_call_log_raw.
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
    provider_invoked BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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

CREATE INDEX IF NOT EXISTS idx_ai_llm_raw_created
    ON cargotech.ai_llm_call_log_raw (created_at);

REVOKE ALL ON TABLE cargotech.ai_llm_call_log_raw FROM PUBLIC;

COMMENT ON COLUMN cargotech.ai_llm_call_logs.user_id IS
    'User who initiated the claim AI generation, propagated by claim-service';

COMMENT ON COLUMN cargotech.ai_llm_call_logs.provider_invoked IS
    'TRUE only when the GigaChat chat endpoint was actually attempted';

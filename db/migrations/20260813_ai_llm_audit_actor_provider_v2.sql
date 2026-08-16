-- Complete LLM audit attribution and count only real provider chat attempts.
-- Safe/idempotent migration for the 2026-08-13 AI acceptance tail.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

ALTER TABLE cargotech.ai_llm_call_logs
    ADD COLUMN IF NOT EXISTS user_id UUID;

ALTER TABLE cargotech.ai_llm_call_logs
    ADD COLUMN IF NOT EXISTS provider_invoked BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill only states that are provably after the model endpoint was invoked.
-- Local validation / limit failures remain FALSE and therefore do not consume
-- the "max 8 LLM calls per claim" quota.
UPDATE cargotech.ai_llm_call_logs
SET provider_invoked = TRUE
WHERE provider_invoked = FALSE
  AND (
       status = 'SUCCESS'
       OR COALESCE(error_message, '') ~ '^[45][0-9]{2}[[:space:]]'
       OR COALESCE(error_message, '') LIKE 'LLM response contains unresolved sensitive-data placeholder:%'
       OR COALESCE(error_message, '') = 'GigaChat returned empty response'
  );

CREATE INDEX IF NOT EXISTS idx_ai_llm_claim_provider_invoked
    ON cargotech.ai_llm_call_logs (claim_id, provider_invoked);

CREATE INDEX IF NOT EXISTS idx_ai_llm_user_created
    ON cargotech.ai_llm_call_logs (user_id, created_at DESC);

-- Raw text is intentionally not exposed to PUBLIC. The application database
-- owner keeps write access; normal diagnostics read only ai_llm_call_logs.
REVOKE ALL ON TABLE cargotech.ai_llm_call_log_raw FROM PUBLIC;

COMMENT ON COLUMN cargotech.ai_llm_call_logs.user_id IS
    'User who initiated the claim AI generation, propagated by claim-service';

COMMENT ON COLUMN cargotech.ai_llm_call_logs.provider_invoked IS
    'TRUE only when the GigaChat chat endpoint was actually attempted';

COMMIT;

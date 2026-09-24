-- Split diagnostics-safe masked LLM audit from restricted raw content.
-- Existing raw content is copied first and then cleared from the masked table.
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

CREATE TABLE IF NOT EXISTS cargotech.ai_llm_call_log_raw (
    request_id VARCHAR(128) PRIMARY KEY,
    raw_prompt TEXT,
    raw_response TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO cargotech.ai_llm_call_log_raw (request_id, raw_prompt, raw_response, created_at)
SELECT request_id, raw_prompt, raw_response, COALESCE(created_at, CURRENT_TIMESTAMP)
FROM cargotech.ai_llm_call_logs
WHERE request_id IS NOT NULL
  AND (raw_prompt IS NOT NULL OR raw_response IS NOT NULL)
ON CONFLICT (request_id) DO UPDATE SET
    raw_prompt = EXCLUDED.raw_prompt,
    raw_response = EXCLUDED.raw_response;

-- The normal audit table becomes the masked copy. Keep legacy columns for
-- backward schema compatibility, but do not retain raw content there.
UPDATE cargotech.ai_llm_call_logs
SET raw_prompt = NULL,
    raw_response = NULL
WHERE raw_prompt IS NOT NULL OR raw_response IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ai_llm_raw_created
    ON cargotech.ai_llm_call_log_raw (created_at);

-- Raw audit content must never be accessible through PostgreSQL's implicit
-- PUBLIC privileges. The application owner still has access for writes.
REVOKE ALL ON TABLE cargotech.ai_llm_call_log_raw FROM PUBLIC;

COMMIT;

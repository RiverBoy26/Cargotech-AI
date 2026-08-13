#!/usr/bin/env bash
set -euo pipefail

CLAIM_ID="${1:-}"
CLAIM_FILTER=""
if [[ -n "$CLAIM_ID" ]]; then
  CLAIM_FILTER="WHERE claim_id = '$CLAIM_ID'"
fi

docker compose exec -T database sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<SQL
\pset pager off
\echo '--- masked audit ---'
SELECT COUNT(*) AS total_calls,
       COUNT(*) FILTER (WHERE masked_prompt IS NOT NULL) AS with_masked_prompt,
       COUNT(*) FILTER (WHERE masked_response IS NOT NULL) AS with_masked_response,
       COUNT(*) FILTER (WHERE raw_prompt IS NOT NULL OR raw_response IS NOT NULL) AS raw_leaks_in_masked_table,
       COALESCE(SUM(total_tokens),0) AS total_tokens,
       COALESCE(SUM(cost_rub),0) AS total_cost_rub
FROM cargotech.ai_llm_call_logs
$CLAIM_FILTER;

\echo '--- restricted raw audit ---'
SELECT COUNT(*) AS raw_rows,
       COUNT(*) FILTER (WHERE raw_prompt IS NOT NULL) AS with_raw_prompt,
       COUNT(*) FILTER (WHERE raw_response IS NOT NULL) AS with_raw_response
FROM cargotech.ai_llm_call_log_raw r
WHERE EXISTS (
  SELECT 1 FROM cargotech.ai_llm_call_logs m
  WHERE m.request_id = r.request_id
  ${CLAIM_ID:+AND m.claim_id = '$CLAIM_ID'}
);
SQL

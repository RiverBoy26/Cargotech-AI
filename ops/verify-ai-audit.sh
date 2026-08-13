#!/usr/bin/env bash
set -euo pipefail

CLAIM_ID="${1:-}"
CLAIM_FILTER=""
if [[ -n "$CLAIM_ID" ]]; then
  CLAIM_FILTER="WHERE claim_id = '$CLAIM_ID'"
fi

docker compose exec -T database sh -lc 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<SQL
\pset pager off
\echo '--- masked audit metadata ---'
SELECT COUNT(*) AS audit_rows,
       COUNT(*) FILTER (WHERE provider_invoked) AS provider_calls,
       COUNT(*) FILTER (WHERE NOT provider_invoked) AS local_only_rows,
       COUNT(*) FILTER (WHERE user_id IS NOT NULL) AS with_user_id,
       COUNT(*) FILTER (WHERE masked_prompt IS NOT NULL) AS with_masked_prompt,
       COUNT(*) FILTER (WHERE masked_response IS NOT NULL) AS with_masked_response,
       COUNT(*) FILTER (WHERE raw_prompt IS NOT NULL OR raw_response IS NOT NULL) AS raw_leaks_in_masked_table,
       COALESCE(SUM(total_tokens) FILTER (WHERE provider_invoked),0) AS provider_tokens,
       COALESCE(SUM(cost_rub) FILTER (WHERE provider_invoked),0) AS provider_cost_rub
FROM cargotech.ai_llm_call_logs
$CLAIM_FILTER;

\echo '--- restricted raw audit (content is not printed) ---'
SELECT COUNT(*) AS raw_rows,
       COUNT(*) FILTER (WHERE raw_prompt IS NOT NULL) AS with_raw_prompt,
       COUNT(*) FILTER (WHERE raw_response IS NOT NULL) AS with_raw_response
FROM cargotech.ai_llm_call_log_raw r
WHERE EXISTS (
  SELECT 1 FROM cargotech.ai_llm_call_logs m
  WHERE m.request_id = r.request_id
  ${CLAIM_ID:+AND m.claim_id = '$CLAIM_ID'}
);

\echo '--- raw table ACL ---'
SELECT grantee, privilege_type
FROM information_schema.table_privileges
WHERE table_schema='cargotech'
  AND table_name='ai_llm_call_log_raw'
ORDER BY grantee, privilege_type;

\echo '--- retention config is application-side; expected default = 365 days ---'
SQL

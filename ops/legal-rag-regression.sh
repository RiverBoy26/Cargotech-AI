#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${AI_BASE_URL:-http://localhost:8084}"

check() {
  local label="$1" query="$2" claim_type="$3" expected="$4"
  echo "=== $label ==="
  body=$(printf '{"query":"%s","filters":{"rag_collection":"LEGAL_CONTEXT","claim_type":["%s","ALL"],"is_current":true,"auto_use":true},"limit":10,"min_score":0.0}' "$query" "$claim_type")
  response=$(curl -fsS -X POST "$BASE_URL/api/ai/rag/search/chunks" -H 'Content-Type: application/json' -d "$body")
  echo "$response" | grep -q "$expected" || {
    echo "FAIL: expected $expected" >&2
    echo "$response" >&2
    exit 1
  }
  echo "PASS: $expected"
}

check "309" "надлежащее исполнение обязательств" "PAYMENT_DELAY" '"article":"309"'
check "310" "односторонний отказ от исполнения обязательства" "PAYMENT_DELAY" '"article":"310"'
check "314" "срок исполнения обязательства просрочка" "PAYMENT_DELAY" '"article":"314"'
check "330" "договорная неустойка штраф пени" "PAYMENT_DELAY" '"article":"330"'
check "395" "проценты за просрочку денежного обязательства ключевая ставка" "PAYMENT_DELAY" '"article":"395"'
check "801" "договор транспортной экспедиции услуги экспедитора за вознаграждение" "PAYMENT_DELAY" '"article":"801"'
check "797" "обязательная претензия перевозчику до иска перевозка груза" "LOADING_FAILURE" '"article":"797"'
check "УАТ 39" "порядок предъявления претензии перевозчику непредоставление транспортного средства" "LOADING_FAILURE" '"article":"39"'
check "УАТ 40" "срок письменного ответа перевозчика на претензию тридцать дней" "LOADING_FAILURE" '"article":"40"'

echo "LEGAL RAG REGRESSION: PASS"

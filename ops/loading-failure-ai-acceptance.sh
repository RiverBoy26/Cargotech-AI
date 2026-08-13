#!/usr/bin/env bash
set -euo pipefail

AI_URL="${AI_URL:-http://localhost:8084}"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

cat > "$TMP" <<'JSON'
{
  "case_facts": {
    "claim_id": "lf-live-acceptance-001",
    "claim_number": "LF-AI-001",
    "claim_type": "LOADING_FAILURE",
    "creditor": {
      "name": "ООО Клиент-Заказчик",
      "inn": "7700000000",
      "legal_address": "г. Москва"
    },
    "debtor": {
      "name": "ООО Перевозчик",
      "inn": "7800000000",
      "legal_address": "г. Санкт-Петербург"
    },
    "contract": {
      "contract_number": "LF-77/2026",
      "contract_date": "05.02.2026"
    },
    "shipment": {
      "order_number": "ORD-LF-200",
      "route": "Москва — Казань",
      "act_number": "ACT-LF-200",
      "act_date": "12.06.2026",
      "loading_date": "12.06.2026",
      "loading_address": "Москва, склад №4",
      "loading_time_window": "09:00–12:00",
      "vehicle_requirements": "тент 20 т",
      "carrier_name": "ООО Перевозчик",
      "failure_confirmed_by_dispatcher": true
    },
    "payment": null,
    "claim_date": "13.08.2026",
    "signatory": {
      "name": "Дмитриев Павел Алексеевич",
      "position": "Юрист"
    }
  },
  "backend_calculation": {
    "principal_debt": 0,
    "penalty_type": "CONTRACT_PENALTY",
    "penalty_rate_text": "фиксированный штраф",
    "overdue_days": 0,
    "penalty_amount": 15000,
    "total_amount": 15000,
    "currency": "RUB",
    "formula_text": "15000"
  },
  "contract_context": [
    {
      "chunk_id": "lf-contract-5-1",
      "clause_number": "5.1",
      "section_title": "Подача транспортного средства",
      "text": "Перевозчик обязан предоставить транспортное средство к согласованным месту и времени погрузки."
    },
    {
      "chunk_id": "lf-contract-6-4",
      "clause_number": "6.4",
      "section_title": "Ответственность",
      "text": "За непредоставление транспортного средства перевозчик уплачивает штраф 15 000 рублей."
    }
  ],
  "legal_context": [
    {
      "chunk_id": "chunk_legal_gk_330",
      "law_code": "ГК РФ",
      "article": "330",
      "purpose": "неустойка",
      "text": "Неустойка является установленной законом или договором денежной суммой.",
      "citation": "ст. 330 ГК РФ",
      "verified_at": "2026-08-13",
      "applicability": "ALL"
    }
  ],
  "template_context": {
    "template_id": "lf-default-existing",
    "template_name": "Существующая структура LOADING_FAILURE",
    "template_type": "LOADING_FAILURE",
    "template_structure": [
      "Стороны и договор",
      "Факты непредоставления транспортного средства",
      "Правовое основание",
      "Требование"
    ]
  },
  "similar_examples": [],
  "rag_options": {
    "enabled": false,
    "contract_id": null,
    "client_id": null,
    "organization_id": null
  }
}
JSON

echo "POST $AI_URL/api/ai/claims/generate"
curl -fsS -X POST "$AI_URL/api/ai/claims/generate" \
  -H 'Content-Type: application/json' \
  --data-binary @"$TMP"
echo

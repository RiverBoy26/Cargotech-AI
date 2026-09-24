#!/usr/bin/env sh
set -eu

AI_URL="${AI_URL:-http://127.0.0.1:8084}"
QDRANT_URL="${QDRANT_URL:-http://127.0.0.1:6333}"
COLLECTION="${QDRANT_COLLECTION_NAME:-cargotech_rag_chunks}"

printf 'Qdrant collection: '
curl -fsS "$QDRANT_URL/collections/$COLLECTION" > /tmp/cargotech-rag-collection.json
python3 - <<'PY'
import json
value=json.load(open('/tmp/cargotech-rag-collection.json', encoding='utf-8'))
result=value['result']
print(f"status={result['status']} points_count={result['points_count']}")
if result['status'] != 'green':
    raise SystemExit('Qdrant collection is not green')
if result['points_count'] != 42:
    raise SystemExit(f"Expected 42 points, got {result['points_count']}")
PY

curl -fsS -X POST "$AI_URL/api/ai/rag/search/chunks" \
  -H 'Content-Type: application/json' \
  --data '{"query":"просрочка оплаты проценты неустойка срок исполнения","filters":{"rag_collection":"LEGAL_CONTEXT","claim_type":"PAYMENT_DELAY","is_current":true,"auto_use":true},"limit":8,"min_score":0.0}' \
  > /tmp/cargotech-rag-payment-search.json

python3 - <<'PY'
import json
value=json.load(open('/tmp/cargotech-rag-payment-search.json', encoding='utf-8'))
ids=[hit['chunk']['chunkId'] for hit in value.get('hits', [])]
print('payment_hits=', len(ids), ids)
if not ids:
    raise SystemExit('Payment-delay legal search returned no hits')
if not all(chunk_id.startswith('legal-payment-') for chunk_id in ids):
    raise SystemExit('Payment search returned a non-payment legal chunk')
if not all((hit.get('chunk') or {}).get('extra', {}).get('auto_use') is True for hit in value.get('hits', [])):
    raise SystemExit('Payment search returned a review-only legal chunk')
PY

curl -fsS -X POST "$AI_URL/api/ai/rag/search/chunks" \
  -H 'Content-Type: application/json' \
  --data '{"query":"непредоставление транспортного средства срыв погрузки","filters":{"rag_collection":"LEGAL_CONTEXT","claim_type":"LOADING_FAILURE","is_current":true,"auto_use":true},"limit":8,"min_score":0.0}' \
  > /tmp/cargotech-rag-loading-search.json

python3 - <<'PY'
import json
value=json.load(open('/tmp/cargotech-rag-loading-search.json', encoding='utf-8'))
ids=[hit['chunk']['chunkId'] for hit in value.get('hits', [])]
print('loading_hits=', len(ids), ids)
if not ids:
    raise SystemExit('Loading-failure legal search returned no hits')
if not all(chunk_id.startswith('legal-loading-') for chunk_id in ids):
    raise SystemExit('Loading search returned a non-loading legal chunk')
if not all((hit.get('chunk') or {}).get('extra', {}).get('auto_use') is True for hit in value.get('hits', [])):
    raise SystemExit('Loading search returned a review-only legal chunk')
PY

curl -fsS -X POST "$AI_URL/api/ai/rag/search/chunks" \
  -H 'Content-Type: application/json' \
  --data '{"query":"персональные данные электронная подпись хранение первичных документов","filters":{"rag_collection":"LEGAL_CONTEXT","claim_type":"COMMON","is_current":true,"auto_use":false},"limit":8,"min_score":0.0}' \
  > /tmp/cargotech-rag-common-search.json

python3 - <<'PY_COMMON'
import json
value=json.load(open('/tmp/cargotech-rag-common-search.json', encoding='utf-8'))
hits=value.get('hits', [])
ids=[hit['chunk']['chunkId'] for hit in hits]
print('common_review_hits=', len(ids), ids)
if not ids:
    raise SystemExit('Common compliance search returned no hits')
if not all(chunk_id.startswith('legal-common-') for chunk_id in ids):
    raise SystemExit('Common compliance search returned an unrelated chunk')
if not all((hit.get('chunk') or {}).get('extra', {}).get('auto_use') is False for hit in hits):
    raise SystemExit('Common compliance corpus contains an auto-use chunk')
PY_COMMON

printf 'DIRECT SEARCH CHECKS PASSED\n'

curl -fsS -X POST "$AI_URL/api/ai/rag/search/context/payment-delay" \
  -H 'Content-Type: application/json' \
  --data '{"contract_id":"smoke-contract","client_id":"smoke-client"}' \
  > /tmp/cargotech-rag-payment-context.json

python3 - <<'PY'
import json
value=json.load(open('/tmp/cargotech-rag-payment-context.json', encoding='utf-8'))
context=value.get('rag_context') or {}
laws=context.get('legalContext') or []
template=context.get('templateContext')
examples=context.get('similarExamples') or []
print('pipeline_context: legal=', len(laws), 'template=', bool(template), 'examples=', len(examples))
if not laws:
    raise SystemExit('Production payment-delay RAG context returned no legal articles at configured score threshold')
if not template:
    raise SystemExit('Production payment-delay RAG context returned no template')
PY

printf 'PRODUCTION CONTEXT CHECK PASSED\n'
printf 'RAG SMOKE TEST PASSED\n'

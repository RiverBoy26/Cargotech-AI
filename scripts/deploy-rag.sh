#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"

printf '\n[1/6] Validating RAG seed files\n'
python3 scripts/validate-rag-data.py

printf '\n[2/6] Building AI and claim services\n'
docker compose build ai claim

printf '\n[3/6] Recreating AI and claim services\n'
docker compose up -d --no-deps --force-recreate ai claim

printf '\n[4/6] Replacing the old demo collection with the verified corpus\n'
RAG_SEED_RESET_COLLECTION=true docker compose --profile seed run --rm qdrant-seed

printf '\n[5/6] Running RAG smoke tests\n'
sh scripts/rag-smoke-test.sh

printf '\n[6/6] Current service status\n'
docker compose ps

printf '\nRAG DEPLOYMENT PASSED\n'

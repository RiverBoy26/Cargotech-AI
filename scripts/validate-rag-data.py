#!/usr/bin/env python3
"""Static validation for CargoTech RAG seed files. No third-party packages required."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "rag-data"
REQUIRED_CHUNK = {"chunk_id", "rag_collection", "chunk_type", "text", "citation", "is_current", "extra"}
PASSPORT = re.compile(r"\b\d{4}\s?\d{6}\b")
BANK_ACCOUNT = re.compile(r"\b\d{20}\b")
CARD = re.compile(r"\b\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?\d{4}\b")
PHONE = re.compile(r"(?:\+7|8)[\s(-]?\d{3}[\s)-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}")
EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")
VEHICLE_NUMBER = re.compile(r"(?iu)(?<![А-ЯA-Z0-9])[АВЕКМНОРСТУХABEKMHOPCTYX]\s?\d{3}\s?[АВЕКМНОРСТУХABEKMHOPCTYX]{2}\s?\d{2,3}(?![А-ЯA-Z0-9])")


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from strings(nested)


def main() -> int:
    errors: list[str] = []
    ids: set[str] = set()
    total = 0
    active_legal = 0
    review_legal = 0
    templates = 0

    files = sorted(DATA_DIR.glob("*.json"))
    if not files:
        print(f"No JSON files found in {DATA_DIR}", file=sys.stderr)
        return 1

    for path in files:
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            fail(errors, f"{path.name}: invalid JSON: {exc}")
            continue

        if not document.get("source_batch_id"):
            fail(errors, f"{path.name}: source_batch_id is required")
        if not document.get("source_system"):
            fail(errors, f"{path.name}: source_system is required")
        chunks = document.get("chunks")
        if not isinstance(chunks, list) or not chunks:
            fail(errors, f"{path.name}: chunks must be a non-empty list")
            continue

        for index, chunk in enumerate(chunks):
            total += 1
            prefix = f"{path.name}:chunks[{index}]"
            missing = REQUIRED_CHUNK - set(chunk)
            if missing:
                fail(errors, f"{prefix}: missing fields {sorted(missing)}")
                continue
            chunk_id = chunk.get("chunk_id")
            if not isinstance(chunk_id, str) or not chunk_id.strip():
                fail(errors, f"{prefix}: blank chunk_id")
            elif chunk_id in ids:
                fail(errors, f"{prefix}: duplicate chunk_id {chunk_id}")
            else:
                ids.add(chunk_id)

            if chunk.get("claim_type") not in {"PAYMENT_DELAY", "LOADING_FAILURE"}:
                fail(errors, f"{prefix}: unsupported claim_type {chunk.get('claim_type')!r}")
            if not isinstance(chunk.get("is_current"), bool):
                fail(errors, f"{prefix}: is_current must be boolean")

            extra = chunk.get("extra")
            if not isinstance(extra, dict):
                fail(errors, f"{prefix}: extra must be an object")
                continue

            collection = chunk.get("rag_collection")
            if collection == "LEGAL_CONTEXT":
                required_legal = {"law_code", "article", "purpose", "verified_at", "applicability", "source_url", "source_edition", "auto_use", "review_required"}
                absent = required_legal - set(extra)
                if absent:
                    fail(errors, f"{prefix}: missing legal metadata {sorted(absent)}")
                if not isinstance(extra.get("auto_use"), bool):
                    fail(errors, f"{prefix}: auto_use must be boolean")
                elif extra["auto_use"]:
                    active_legal += 1
                    if extra.get("review_required") is not False:
                        fail(errors, f"{prefix}: auto_use=true requires review_required=false")
                else:
                    review_legal += 1
                    if extra.get("review_required") is not True:
                        fail(errors, f"{prefix}: auto_use=false requires review_required=true")
            elif collection == "TEMPLATE_CONTEXT":
                templates += 1
                for field in ("template_id", "template_name", "template_structure"):
                    if not extra.get(field):
                        fail(errors, f"{prefix}: extra.{field} is required")
            elif collection == "SIMILAR_EXAMPLE":
                for field in ("example_id", "usage_rule", "structure_summary"):
                    if not extra.get(field):
                        fail(errors, f"{prefix}: extra.{field} is required")
            else:
                fail(errors, f"{prefix}: unexpected rag_collection {collection!r}")

            for text in strings(chunk):
                if PASSPORT.search(text):
                    fail(errors, f"{prefix}: possible passport data")
                if BANK_ACCOUNT.search(text):
                    fail(errors, f"{prefix}: possible bank account")
                if CARD.search(text):
                    fail(errors, f"{prefix}: possible card number")
                if PHONE.search(text):
                    fail(errors, f"{prefix}: possible phone number")
                if EMAIL.search(text):
                    fail(errors, f"{prefix}: possible email")
                if VEHICLE_NUMBER.search(text):
                    fail(errors, f"{prefix}: possible vehicle number")
                if "Ð" in text or "Ñ" in text or "�" in text:
                    fail(errors, f"{prefix}: possible broken UTF-8/mojibake")
                if text.count("?") >= 3:
                    fail(errors, f"{prefix}: too many question marks; text may be corrupted")

    if total != 28:
        fail(errors, f"expected 28 chunks, found {total}")
    if active_legal != 16:
        fail(errors, f"expected 16 auto-use legal chunks, found {active_legal}")
    if review_legal != 8:
        fail(errors, f"expected 8 review-only legal chunks, found {review_legal}")
    if templates != 2:
        fail(errors, f"expected 2 templates, found {templates}")

    if errors:
        print("RAG DATA VALIDATION FAILED", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print("RAG DATA VALIDATION PASSED")
    print(f"files={len(files)} chunks={total} auto_use_legal={active_legal} review_only_legal={review_legal} templates={templates}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

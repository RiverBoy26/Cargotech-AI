"""Расчёт неустойки: договорная (пени за день) либо ст.395 ГК (Q11-Q12).

Одновременное применение запрещено (п.4 ст.395) — выбор взаимоисключающий.
"""
import re
from datetime import date

# Ключевая ставка ЦБ (в проде — из справочника по периодам; здесь одна для демо).
KEY_RATE = 0.16


def _find_daily_rate(clauses: list[dict]) -> float | None:
    """Ищем в пунктах-неустойке ставку пени ИМЕННО за просрочку ОПЛАТЫ (напр. '0,1% за день')."""
    for c in clauses:
        if c.get("topic") != "неустойка":
            continue
        text = c.get("text", "").lower()
        if not any(w in text for w in ("оплат", "платеж", "платёж", "денежн", "просрочк")):
            continue                       # фикс. штраф не за оплату — не годится для пени по долгу
        m = re.search(r"(\d+[.,]?\d*)\s*%.*?(?:за\s+кажд\w*\s+день|в\s+день|за\s+день)", text)
        if m:
            return float(m.group(1).replace(",", ".")) / 100
    return None


def compute(principal: float, due_date: date, as_of: date,
            penalty_basis: str, clauses: list[dict]) -> dict:
    """principal — сумма долга; due_date — дата, с которой считается просрочка."""
    days = max((as_of - due_date).days, 0)

    daily = _find_daily_rate(clauses) if penalty_basis == "contract" else None

    if daily is not None:
        amount = round(principal * daily * days, 2)
        return {
            "method": "договорная неустойка",
            "basis": penalty_basis,
            "days": days,
            "formula": f"{principal:,.2f} × {daily*100:.3g}% × {days} дн.",
            "amount": amount,
            "flags": [],
        }

    # фолбэк: договорной пени за просрочку ОПЛАТЫ нет -> ст.395 ГК
    amount = round(principal * KEY_RATE * days / 365, 2)
    flags = []
    if penalty_basis == "contract":
        flags.append("no_payment_penalty_rate_in_contract:fallback_395")
    return {
        "method": "проценты по ст.395 ГК РФ",
        "basis": "art_395",
        "days": days,
        "formula": f"{principal:,.2f} × {KEY_RATE*100:.0f}%/365 × {days} дн.",
        "amount": amount,
        "flags": flags,
    }

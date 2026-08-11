"""Guardrail-агент: второй проход-проверка перед юристом (Q24).

Rule-based чекер (в MVP допустим + один LLM-вызов). Ловит critical-ошибки (Q21):
несуществующий пункт договора, расхождение сумм, утечка немаскированных ПДн-плейсхолдеров.
"""
import re

CLAUSE_IN_TEXT = re.compile(r"п\.?\s?(\d+(?:\.\d+){0,4})")
PLACEHOLDER = re.compile(r"<(?:PERSON|PASSPORT|PHONE|EMAIL|BANK_ACC|CARD|ADDRESS)_\d+>")
MONEY = re.compile(r"(\d[\d\s]*,\d{2})\s*руб")


def _to_float(s: str) -> float:
    return float(s.replace(" ", "").replace(",", "."))


def check(draft: str, rag_obj: dict, gen_meta: dict, facts: dict) -> dict:
    findings = []

    # 1) КАЖДАЯ ссылка на пункт договора должна существовать в retrieved-контексте (Q10, Q21)
    valid_clauses = {c["clause_number"] for c in rag_obj["contract_clauses"]
                     if c["clause_number"]}
    for m in CLAUSE_IN_TEXT.finditer(draft):
        num = m.group(1)
        # пропускаем ссылки на статьи АПК/нормы (они не пункты договора)
        if num in valid_clauses:
            continue
        # ссылка на пункт, которого нет в retrieved договоре -> фабрикация
        findings.append(("CRITICAL", f"ссылка на п.{num} отсутствует в retrieved-контексте договора"))

    # 2) суммы в тексте совпадают с расчётом (Q21: неверная сумма долга -> обнуление)
    money_in_text = {round(_to_float(m.group(1)), 2) for m in MONEY.finditer(draft)}
    for label, val in [("долг", gen_meta["claimed_debt"]),
                       ("неустойка", gen_meta["claimed_penalty"]),
                       ("итого", gen_meta["claimed_total"])]:
        if round(val, 2) not in money_in_text:
            findings.append(("CRITICAL", f"сумма '{label}' ({val}) не найдена дословно в тексте"))

    # 3) итого = долг + неустойка
    if round(gen_meta["claimed_total"], 2) != round(
            gen_meta["claimed_debt"] + gen_meta["claimed_penalty"], 2):
        findings.append(("CRITICAL", "итоговая сумма ≠ долг + неустойка"))

    # 4) утечка немаскированных ПДн-плейсхолдеров в финальный текст (Q7, Q21)
    leaks = PLACEHOLDER.findall(draft)
    if leaks:
        findings.append(("CRITICAL", f"незамаскированные плейсхолдеры в тексте: {leaks}"))

    # 5) обязательные реквизиты адресата (Q24)
    if facts["debtor"]["inn"] not in draft:
        findings.append(("WARN", "в претензии нет ИНН должника"))
    if facts["debtor"]["name"] not in draft:
        findings.append(("WARN", "в претензии нет наименования должника"))

    # 6) правовое основание согласовано между расчётом и RAG-объектом
    if rag_obj.get("penalty_basis") == "contract" and "395" in draft and \
            not any(c["topic"] == "неустойка" for c in rag_obj["contract_clauses"]):
        findings.append(("WARN", "basis=contract, но в тексте ст.395 без договорной неустойки"))

    # проброс флагов RAG и расчёта в отчёт
    for f in rag_obj.get("flags", []):
        findings.append(("REVIEW", f"RAG flag: {f}"))

    critical = [f for f in findings if f[0] == "CRITICAL"]
    verdict = "BLOCK" if critical else ("REVIEW" if findings else "PASS")
    return {"verdict": verdict, "findings": findings}

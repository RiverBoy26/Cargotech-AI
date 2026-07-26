"""End-to-end: кейс просрочки → RAG → расчёт неустойки → черновик претензии → guardrail."""
from datetime import date

from build_index import build_all
from rag.retriever import Retriever
from rag import penalty as penalty_mod
from rag import generator, guardrail


def hr(t=""):
    print("\n" + "═" * 78)
    if t:
        print(t); print("─" * 78)


# ── синтетический кейс (в проде: из БД CargoTech + выгрузка 1С) ──
CASE = {
    "contract_number": "ТЭУ-Росинка/2024", "contract_date": "2024-05-13",
    "trip": "Москва–Казань, 20 т", "act_date": "2025-03-12",
    "due_date": "2025-04-11",            # акт + 30 дн срок оплаты
    "as_of": "2025-07-01", "amount": 450000.00, "claim_deadline_days": 30,
    "creditor": {"name": "ООО «Маршал МТЛ»", "inn": "7708123456"},
    "debtor": {"name": "АО «Компания Росинка»", "inn": "7701234567",
               "address": "г. Москва, ул. Примерная, д. 1"},
}


def run_case(store, templates):
    ret = Retriever(store, templates)
    rag_obj = ret.retrieve(claim_type="просрочка оплаты", client_id="rosinka",
                           contract_id="C-ROSINKA-2024", contract_type="ТЭУ",
                           trip_context=f'{CASE["trip"]}, акт {CASE["act_date"]}, оплата не поступила')

    pen = penalty_mod.compute(
        principal=CASE["amount"],
        due_date=date.fromisoformat(CASE["due_date"]),
        as_of=date.fromisoformat(CASE["as_of"]),
        penalty_basis=rag_obj["penalty_basis"],
        clauses=rag_obj["contract_clauses"])

    # авторитетное основание — фактический расчёт (учёл применимость пени к оплате)
    rag_obj["penalty_basis"] = pen["basis"]
    rag_obj["flags"] = rag_obj.get("flags", []) + pen["flags"]

    draft, meta = generator.generate(rag_obj, CASE, pen)
    return rag_obj, pen, draft, meta


def main():
    store, templates, masker = build_all()

    hr("ШАГ A. RAG → расчёт неустойки")
    rag_obj, pen, draft, meta = run_case(store, templates)
    print(f'  penalty_basis (RAG): {rag_obj["penalty_basis"]}')
    print(f'  метод расчёта:       {pen["method"]}')
    print(f'  формула:             {pen["formula"]} = {pen["amount"]:,.2f} руб.')
    print(f'  флаги расчёта:       {pen["flags"] or "нет"}')

    hr("ШАГ B. Черновик претензии (GigaChat-слот; здесь детерминир. сборка)")
    print(draft)

    hr("ШАГ C. Guardrail-агент (проверка перед юристом, Q24)")
    rep = guardrail.check(draft, rag_obj, meta, CASE)
    print(f'  ВЕРДИКТ: {rep["verdict"]}')
    for level, msg in rep["findings"]:
        print(f'    [{level}] {msg}')
    if not rep["findings"]:
        print("    (замечаний нет)")

    # ── НЕГАТИВНЫЙ ТЕСТ: портим черновик, guardrail обязан заблокировать ──
    hr("ШАГ D. Негативный тест — инъекция галлюцинации (Q21)")
    bad = draft.replace("ТРЕБУЕМ:",
                        "На основании п. 99.9 договора (несуществующий) ТРЕБУЕМ:")
    bad = bad.replace(f'{guardrail_fmt(meta["claimed_debt"])} руб.',
                      "999 999,00 руб.", 1)
    bad += "\nВодитель <PERSON_7> не маскирован."
    rep2 = guardrail.check(bad, rag_obj, meta, CASE)
    print(f'  ВЕРДИКТ: {rep2["verdict"]}  (ожидается BLOCK)')
    for level, msg in rep2["findings"]:
        print(f'    [{level}] {msg}')

    hr("ГОТОВО — полный контур: RAG → расчёт → черновик → guardrail")


def guardrail_fmt(n):
    return f"{n:,.2f}".replace(",", " ").replace(".", ",")


if __name__ == "__main__":
    main()

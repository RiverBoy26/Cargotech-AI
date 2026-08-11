"""Генератор черновика претензии.

В проде здесь GigaChat Pro: prompt = шаблон + контракт-объект RAG + факты (≤32K, Q25).
В прототипе — детерминированная сборка по слотам (тот же контракт данных, без ключа).
Все ссылки на пункты берутся ТОЛЬКО из retrieved-контекста (заземление, анти-галлюцинация).
"""
from datetime import date


def _fmt(n: float) -> str:
    return f"{n:,.2f}".replace(",", " ").replace(".", ",")


def generate(rag_obj: dict, facts: dict, penalty: dict) -> str:
    creditor = facts["creditor"]
    debtor = facts["debtor"]
    # ссылки на нормы — только из selector'а норм RAG
    norm_refs = ", ".join(f'ст. {n["article"]} {n["code"]}' for n in rag_obj["legal_norms"])
    # пункт-неустойку цитируем ТОЛЬКО если расчёт реально пошёл по договору,
    # иначе (fallback на ст.395) ссылка на договорный пункт была бы неприменимой
    penalty_clause = None
    if penalty["basis"] == "contract":
        penalty_clause = next((c for c in rag_obj["contract_clauses"]
                               if c["topic"] == "неустойка"), None)
    total = facts["amount"] + penalty["amount"]

    lines = [
        f'{debtor["name"]}',
        f'ИНН {debtor["inn"]}, {debtor["address"]}',
        "",
        f'от {creditor["name"]}, ИНН {creditor["inn"]}',
        "",
        f'ПРЕТЕНЗИЯ об уплате задолженности и неустойки',
        f'по Договору № {facts["contract_number"]} от {facts["contract_date"]}',
        "",
        f'Между {creditor["name"]} (Экспедитор) и {debtor["name"]} (Клиент) заключён '
        f'договор транспортной экспедиции № {facts["contract_number"]} от {facts["contract_date"]}.',
        f'Экспедитор оказал услуги на сумму {_fmt(facts["amount"])} руб., что подтверждается '
        f'актом оказанных услуг от {facts["act_date"]} (рейс: {facts["trip"]}).',
        "",
        f'Согласно условиям договора оплата производится в срок до {facts["due_date"]}. '
        f'В нарушение {("п. " + penalty_clause["clause_number"] + " договора и " ) if penalty_clause else ""}'
        f'{norm_refs} оплата в установленный срок не поступила. '
        f'Период просрочки на {facts["as_of"]} составляет {penalty["days"]} дн.',
        "",
        f'РАСЧЁТ НЕУСТОЙКИ ({penalty["method"]}):',
        f'  {penalty["formula"]} = {_fmt(penalty["amount"])} руб.',
        "",
        f'На основании изложенного и руководствуясь {norm_refs}, ТРЕБУЕМ:',
        f'  1. Погасить задолженность в размере {_fmt(facts["amount"])} руб.',
        f'  2. Уплатить неустойку в размере {_fmt(penalty["amount"])} руб.',
        f'  ИТОГО к уплате: {_fmt(total)} руб.',
        "",
        f'Срок исполнения требований — {facts["claim_deadline_days"]} календарных дней с даты '
        f'получения претензии. При неисполнении спор будет передан в арбитражный суд '
        f'(ч.5 ст.4 АПК РФ).',
        "",
        "Приложения: 1) копия договора; 2) акт оказанных услуг; 3) расчёт неустойки; "
        "4) выписка из 1С по платежам.",
    ]
    draft = "\n".join(lines)
    # метаданные для guardrail — какие суммы/пункты МЫ заявили
    meta = {
        "claimed_debt": facts["amount"],
        "claimed_penalty": penalty["amount"],
        "claimed_total": total,
        "cited_clauses": [penalty_clause["clause_number"]] if penalty_clause else [],
    }
    return draft, meta

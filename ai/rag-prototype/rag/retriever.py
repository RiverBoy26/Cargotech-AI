"""Ретривер: маршрутизация по 3 коллекциям + детерминированные selector'ы.

Собирает контракт-объект (§5.3 архитектуры), который дальше идёт в генератор претензии.
"""
from .store import VectorStore
from .norms import LEGAL_NORMS


class Retriever:
    def __init__(self, contracts: VectorStore, templates: list[dict]):
        self.contracts = contracts        # коллекция contracts (dense+bm25)
        self.templates = templates         # коллекция claim_templates (метаданные + текст)

    # --- selector 1: шаблон по приоритету клиент -> тип -> дефолт (Q16) ---
    def select_template(self, claim_type: str, client_id: str) -> dict:
        by_client = [t for t in self.templates
                     if t.get("client_id") == client_id and t.get("claim_type") == claim_type]
        if by_client:
            return {**by_client[0], "source": "client-specific"}
        by_type = [t for t in self.templates if t.get("claim_type") == claim_type]
        if by_type:
            return {**by_type[0], "source": "type"}
        default = [t for t in self.templates if t.get("is_default")]
        return {**(default[0] if default else self.templates[0]), "source": "default"}

    # --- selector 2: нормы права по приоритету (Q11-Q12) ---
    def select_norms(self, contract_type: str, has_penalty_clause: bool) -> tuple[list[dict], str]:
        norms = [n for n in LEGAL_NORMS if contract_type in n["applies_to"]]
        if has_penalty_clause:
            basis = "contract"                       # договорная неустойка приоритетна
            norms = [n for n in norms if n["article"] != "395"]     # исключаем ст.395 (п.4 ст.395)
        else:
            basis = "art_395"                        # неустойки в договоре нет -> ст.395
        norms.sort(key=lambda n: n["priority"])
        return norms[:4], basis

    # --- главный вход ---
    def retrieve(self, claim_type: str, client_id: str, contract_id: str,
                 contract_type: str, trip_context: str, k=4) -> dict:
        # 1) contracts: hard-filter по contract_id, целевые пункты по каждому топику
        penalty_hits = self.contracts.search(
            "неустойка пени штраф за нарушение обязательств",
            where={"contract_id": contract_id, "clause_topic": "неустойка"}, k=2)
        payment_hits = self.contracts.search(
            "срок и порядок оплаты услуг расчёты между сторонами",
            where={"contract_id": contract_id}, k=2)
        jur_hits = self.contracts.search(
            "подсудность споры арбитражный суд претензионный порядок",
            where={"contract_id": contract_id}, k=2)

        # has_penalty определяем по НАЛИЧИЮ пункта-неустойки в договоре, а не по тому,
        # что случайно попало в top-k общего запроса
        has_penalty = len(penalty_hits) > 0
        clauses = _dedup([h for h, _, _ in penalty_hits + payment_hits + jur_hits])

        # 2) шаблон
        template = self.select_template(claim_type, client_id)

        # 3) нормы
        norms, basis = self.select_norms(contract_type, has_penalty)

        # флаги manual_review (Q10, Q21): если требование о неустойке не заземлено
        flags = []
        if not has_penalty and claim_type == "просрочка оплаты":
            flags.append("penalty_clause_not_found:fallback_395")

        return {
            "claim_type": claim_type,
            "client_id": client_id,
            "template": {"template_id": template.get("template_id"),
                         "title": template.get("title"), "source": template["source"]},
            "contract_clauses": [
                {"clause_number": c.get("clause_number"),
                 "topic": c.get("clause_topic"),
                 "citation": c.get("citation"),
                 "text": c["text"][:220]}
                for c in clauses],
            "legal_norms": [{"code": n["code"], "article": n["article"],
                             "topic": n["topic"]} for n in norms],
            "penalty_basis": basis,
            "flags": flags,
        }


def _dedup(chunks):
    seen, out = set(), []
    for c in chunks:
        key = (c.get("contract_id"), c.get("clause_number"), c["text"][:30])
        if key not in seen:
            seen.add(key)
            out.append(c)
    return out

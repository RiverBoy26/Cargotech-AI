"""Демо RAG-контура AI-юриста: индексация реальных документов + тестовые запросы с дебагом."""
import json
import textwrap

from build_index import build_all
from rag.retriever import Retriever


def hr(title=""):
    print("\n" + "═" * 78)
    if title:
        print(title)
        print("─" * 78)


def show_hits(store, query, where, k=4, alpha=0.6):
    print(f"\n  QUERY: {query!r}   filter={where}")
    hits = store.search(query, where=where, k=k, alpha=alpha)
    if not hits:
        print("    (пусто — фильтр не дал кандидатов)")
    for c, score, dbg in hits:
        cl = c.get("clause_number") or "—"
        topic = c.get("clause_topic") or "—"
        print(f"    score={score:.3f} [dense={dbg['dense']:.2f} bm25={dbg['bm25']:.2f}] "
              f"п.{cl} <{topic}> :: {textwrap.shorten(c['text'], 90)}")
    return hits


def main():
    store, templates, masker = build_all()
    ret = Retriever(store, templates)

    # ── ТЕСТ 1: сырой гибридный retrieve по договору Росинки ──
    hr("ТЕСТ 1. Hybrid retrieve по коллекции contracts (договор Росинка)")
    show_hits(store, "неустойка и штраф за нарушение обязательств",
              where={"contract_id": "C-ROSINKA-2024"})
    show_hits(store, "срок и порядок оплаты услуг клиентом",
              where={"contract_id": "C-ROSINKA-2024"})

    # ── ТЕСТ 2: демонстрация metadata-фильтра (изоляция по client_id) ──
    hr("ТЕСТ 2. Metadata pre-filter — тот же запрос, разные договоры")
    show_hits(store, "ответственность сторон за просрочку",
              where={"contract_id": "C-ROSINKA-2024"}, k=2)
    show_hits(store, "ответственность сторон за просрочку",
              where={"contract_id": "OFERTA-MARSHAL-2025"}, k=2)

    # ── ТЕСТ 3: dense vs bm25 (гибрид ловит и синонимы, и точные термины) ──
    hr("ТЕСТ 3. Вклад dense vs BM25 (alpha=1.0 чистый вектор, 0.0 чистый BM25)")
    show_hits(store, "пени за задержку платежа", where={"contract_id": "C-ROSINKA-2024"}, k=2, alpha=1.0)
    show_hits(store, "пени за задержку платежа", where={"contract_id": "C-ROSINKA-2024"}, k=2, alpha=0.0)

    # ── ТЕСТ 4: selector шаблонов по приоритету (Q16) ──
    hr("ТЕСТ 4. Авто-выбор шаблона: клиент → тип → дефолт (Q16)")
    for cid, ct in [("rosinka", "просрочка оплаты"), ("magnit", "просрочка оплаты"),
                    ("rosinka", "простой ТС")]:
        t = ret.select_template(ct, cid)
        print(f"  client={cid:10} type={ct:18} → {t['template_id']:16} (source={t['source']})")

    # ── ТЕСТ 5: selector норм — договорная неустойка vs ст.395 (Q11-Q12) ──
    hr("ТЕСТ 5. Выбор норм права: договор vs ст.395 ГК (взаимоисключающе, п.4 ст.395)")
    for hp in (True, False):
        norms, basis = ret.select_norms("ТЭУ", has_penalty_clause=hp)
        arts = ", ".join(f"{n['code']} ст.{n['article']}" for n in norms)
        print(f"  есть_неустойка_в_договоре={hp!s:5} → basis={basis:9} | нормы: {arts}")

    # ── ТЕСТ 6: полный контракт-объект для генератора (§5.3) ──
    hr("ТЕСТ 6. Полный RAG-ответ (контракт-объект → в генератор претензии)")
    obj = ret.retrieve(claim_type="просрочка оплаты", client_id="rosinka",
                       contract_id="C-ROSINKA-2024", contract_type="ТЭУ",
                       trip_context="рейс Москва–Казань, акт подписан 12.03.2025, оплата не поступила")
    print(json.dumps(obj, ensure_ascii=False, indent=2))

    # ── ТЕСТ 7: PII-маскирование ──
    hr("ТЕСТ 7. Маскирование ПДн ДО LLM (Q7-Q9) + reverse-mapping")
    sample = ("Водитель Иванов И.И., паспорт 4509 123456, тел. +7 916 123-45-67, "
              "e-mail ivanov@mail.ru, р/с 40702810900000012345. "
              "ООО «Росинка», ИНН 7701234567 — не маскируется.")
    masked = masker.mask(sample)
    print("  ДО:      ", sample)
    print("  ПОСЛЕ:   ", masked)
    print("  REVERSE: ", masker.reverse(masked))

    hr("ГОТОВО")


if __name__ == "__main__":
    main()

# RAG-прототип AI-юриста CargoTech (рабочий, локальный)

Реализация RAG-контура из `../RAG_архитектура_и_план.md` на реальных документах папки `Для бота-юриста`.

## Запуск
```bash
cd rag_bot
HF_HUB_OFFLINE=1 TOKENIZERS_PARALLELISM=false python3 demo.py        # RAG: 7 тестов ретрива
HF_HUB_OFFLINE=1 TOKENIZERS_PARALLELISM=false python3 demo_full.py   # полный контур
```

## Полный контур (demo_full.py)
`кейс просрочки → RAG-контекст → расчёт неустойки → черновик претензии → guardrail`
- `rag/penalty.py` — договорная неустойка / ст.395 ГК, взаимоисключающе (Q11-12).
- `rag/generator.py` — сборка черновика; пункты договора берутся ТОЛЬКО из retrieved-контекста.
- `rag/guardrail.py` — проверка перед юристом (Q24): существование ссылок на пункты (Q21),
  совпадение сумм, утечки ПДн-плейсхолдеров; вердикт PASS/REVIEW/BLOCK.

## Что внутри (3 коллекции из ТЗ Q14)
- `rag/extract.py` — DOCX (вкл. таблицы) / PDF (pdftotext) / XLSX. OCR не в MVP (Q6).
- `rag/pii.py` — маскирование ПДн ДО LLM + reverse-mapping (Q7-Q9).
- `rag/chunk.py` — структурный чанкинг договоров с точным `clause_number` (Q10).
- `rag/norms.py` — выверенный корпус норм (ГК/УАТ/АПК).
- `rag/store.py` — hybrid: dense (multilingual-e5-base) + BM25 + metadata-фильтр.
- `rag/retriever.py` — selector'ы шаблона (Q16) и норм права договор-vs-395 (Q11-12), сборка контракт-объекта (§5.3).

## Отличия прототипа от прод-архитектуры
| Прод (план) | Прототип |
|---|---|
| GigaChat Embeddings | multilingual-e5-base локально (нет ключа) — свап одной строкой в `store.py` |
| pgvector + PostgreSQL FTS | numpy + собственный BM25 |
| cross-encoder reranker | top-k по гибридному score (reranker не подключён) |
| classify_topic | keyword-стемминг (в проде — LLM/классификатор) |

Заглушки шаблонов «просрочка оплаты» синтетические — реальные ~15 шаблонов будут в закрытом датасете (Q15).

## Данные (важно, 152-ФЗ)
Исходные клиентские документы (договоры, акты, оферты, сканы) **намеренно не хранятся в репозитории**.
Прототип ожидает датасет рядом: положите папку `Для бота-юриста/` в родительский каталог
и поправьте `BASE` в `build_index.py`. Все ПДн маскируются на индексации (`rag/pii.py`).

## Статус
Исследовательский прототип RAG-контура для сервиса `ai`. Эмбеддинги — локальный
`multilingual-e5-base` (в проде свап на GigaChat Embeddings), vector store — numpy+BM25
(в проде pgvector + PostgreSQL FTS). См. `ARCHITECTURE_RAG.md`.

"""Структурный чанкинг договоров с сохранением точного номера пункта (Q10).

Ключевое: наивный сплит по размеру ломает нумерацию -> неверная ссылка = critical error.
Здесь один пункт договора = один чанк, с clause_number/section_path/clause_topic.
"""
import re

# Заголовок пункта: "6.1.", "5.3.10.", "15.2." + текст. Допускаем tab/пробелы.
CLAUSE_RE = re.compile(r"^\s*(\d+(?:\.\d+){0,4})\.?\s+(.*)$")
# Заголовок раздела: одиночное число + ЗАГЛАВНЫЙ заголовок, либо строка без цифры-точки.
SECTION_RE = re.compile(r"^\s*(\d+)\.?\s+([А-ЯЁ][А-ЯЁ \-,]{4,})\s*$")

# стемы для матчинга по НАЧАЛУ слова (word.startswith(stem))
_TOPIC_KEYWORDS = {
    "неустойка": ["неустойк", "пени", "пеня", "пеней", "штраф"],
    "срок оплаты": ["оплат", "оплач", "платеж", "платёж", "расчёт", "расчет", "перечисл"],
    "подсудность": ["подсуд", "арбитраж", "третейск", "спор", "споры", "спора"],
    "претензионный_порядок": ["претензи", "досудеб", "претензионн"],
    "ответственность": ["ответственн", "возмещ", "убытк"],
}


_WORD_RE = re.compile(r"[а-яёa-z]+", re.IGNORECASE)


def classify_topic(text: str) -> str | None:
    """Матчинг по началу слова, а не по подстроке — иначе 'спор' ловится в 'транСПОРт'."""
    words = [w.lower() for w in _WORD_RE.findall(text)]
    best, best_hits = None, 0
    for topic, stems in _TOPIC_KEYWORDS.items():
        hits = sum(1 for w in words for s in stems if w.startswith(s))
        if hits > best_hits:
            best, best_hits = topic, hits
    return best


def chunk_contract(text: str, doc_meta: dict, min_len: int = 40) -> list[dict]:
    """Возвращает список чанков-пунктов с метаданными."""
    lines = [l.rstrip() for l in text.split("\n")]
    chunks: list[dict] = []
    section_title = ""
    cur = None

    def flush():
        nonlocal cur
        if cur and len(cur["text"].strip()) >= min_len:
            cur["clause_topic"] = classify_topic(cur["text"])
            chunks.append(cur)
        cur = None

    for line in lines:
        if not line.strip():
            continue
        sec = SECTION_RE.match(line)
        cl = CLAUSE_RE.match(line)
        if sec:
            flush()
            section_title = f'{sec.group(1)}. {sec.group(2).strip().title()}'
            continue
        if cl:
            flush()
            num = cl.group(1)
            cur = {
                "clause_number": num,
                "section_path": section_title,
                "text": cl.group(2).strip(),
                **doc_meta,
                "citation": f'п. {num} Договора № {doc_meta.get("contract_number","?")} '
                            f'от {doc_meta.get("contract_date","?")}',
            }
        elif cur:                       # продолжение текущего пункта
            cur["text"] += " " + line.strip()
        # строки до первого пункта (преамбула) пропускаем
    flush()
    return chunks


def chunk_generic(text: str, doc_meta: dict, size: int = 900, overlap: int = 150) -> list[dict]:
    """Фолбэк для документов без нумерации (оферты/шаблоны) — окна с перекрытием."""
    text = re.sub(r"[ \t]+", " ", text)
    chunks, i, idx = [], 0, 0
    while i < len(text):
        piece = text[i:i + size]
        chunks.append({
            "clause_number": None,
            "section_path": f"фрагмент {idx}",
            "text": piece.strip(),
            "clause_topic": classify_topic(piece),
            **doc_meta,
            "citation": f'{doc_meta.get("contract_number", doc_meta.get("source","документ"))}',
        })
        i += size - overlap
        idx += 1
    return chunks

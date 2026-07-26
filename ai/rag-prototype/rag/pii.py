"""Маскирование ПДн ДО отправки в LLM (Q7-Q9).

Маскируем: ФИО физлиц, паспорт, телефон, личный e-mail, номера счетов/карт.
НЕ маскируем: наименования юрлиц, ИНН/ОГРН, юр.адреса, суммы (публично из ЕГРЮЛ).
reverse() возвращает реальные значения уже в защищённом контуре.
"""
import re

_PATTERNS = [
    ("PASSPORT", re.compile(r"\b\d{4}\s?\d{6}\b")),
    ("BANK_ACC", re.compile(r"\b\d{20}\b")),                       # р/с, 20 цифр
    ("CARD",     re.compile(r"\b\d{4}[ -]?\d{4}[ -]?\d{4}[ -]?\d{4}\b")),
    ("PHONE",    re.compile(r"(?:\+7|8)[\s(-]?\d{3}[\s)-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}")),
    ("EMAIL",    re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")),
    # ФИО физлица: Фамилия И.О. или Фамилия Имя Отчество
    ("PERSON",   re.compile(r"\b[А-Я][а-я]+\s+[А-Я]\.\s?[А-Я]\.")),
]

# ИНН/ОГРН не маскируем, но защищаем от кражи их паттерном PASSPORT/BANK_ACC:
_KEEP = re.compile(r"ИНН\s*\d+|ОГРН\s*\d+|КПП\s*\d+")


class Masker:
    def __init__(self):
        self.map: dict[str, str] = {}   # placeholder -> original (шифрованная таблица)
        self._counters: dict[str, int] = {}

    def _token(self, kind: str, value: str) -> str:
        for ph, orig in self.map.items():
            if orig == value and ph.startswith(f"<{kind}"):
                return ph
        n = self._counters.get(kind, 0) + 1
        self._counters[kind] = n
        ph = f"<{kind}_{n}>"
        self.map[ph] = value
        return ph

    def mask(self, text: str) -> str:
        keep_spans = [(m.start(), m.end()) for m in _KEEP.finditer(text)]

        def protected(pos):
            return any(a <= pos < b for a, b in keep_spans)

        for kind, pat in _PATTERNS:
            def repl(m):
                if protected(m.start()):
                    return m.group(0)
                return self._token(kind, m.group(0))
            text = pat.sub(repl, text)
        return text

    def reverse(self, text: str) -> str:
        for ph, orig in self.map.items():
            text = text.replace(ph, orig)
        return text

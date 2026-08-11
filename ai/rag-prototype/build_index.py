"""Индексация реальных документов из папки 'Для бота-юриста' в 3 коллекции RAG."""
import os
from pathlib import Path

from rag.extract import extract
from rag.pii import Masker
from rag.chunk import chunk_contract, chunk_generic
from rag.store import VectorStore

BASE = Path(__file__).resolve().parent.parent / "Для бота-юриста"

# --- манифест: сопоставление файлов с метаданными (в проде — из карточек БД) ---
CONTRACT_MANIFEST = [
    {"file": "Договора c клиентами/Договор ТЭУ АО Компания Росинка от 13.05.2024 (1).docx",
     "contract_id": "C-ROSINKA-2024", "client_id": "rosinka",
     "contract_number": "ТЭУ-Росинка/2024", "contract_date": "2024-05-13",
     "contour": "exp_client", "contract_type": "ТЭУ", "structured": True},
    {"file": "Договора c клиентами/договор.pdf",
     "contract_id": "C-GENERIC-PDF", "client_id": "generic",
     "contract_number": "б/н", "contract_date": "н/д",
     "contour": "exp_client", "contract_type": "ТЭУ", "structured": True},
    {"file": "Договор с перевозом и офферта/usloviya-Dogovora-transportno-ekspediczionnyh-uslug-ot-22_10_2025g_-Marshal-Samseditor.pdf",
     "contract_id": "OFERTA-MARSHAL-2025", "client_id": "marshal_oferta",
     "contract_number": "Оферта-Marshal/2025-10-22", "contract_date": "2025-10-22",
     "contour": "exp_client", "contract_type": "ТЭУ", "structured": True},
]

# --- коллекция claim_templates: реальные акты/уведомления + синтетические претензии ---
def load_templates(masker: Masker) -> list[dict]:
    tpl_dir = BASE / "Регламент+шаблон+прмеры"
    templates: list[dict] = []
    real_files = {
        "Акт_о_непредоставлении_ТС_на_погрузку (2).docx": ("непредоставление ТС", None),
        "Уведомление_о_составлении_акта_о_непредоставлении_ТС_на_погрузку (1).docx": ("непредоставление ТС", None),
        "Акт к ИП Пирвердиев от ООО МАРШАЛ (1) (1).docx": ("простой ТС", None),
    }
    for fn, (ctype, client) in real_files.items():
        p = tpl_dir / fn
        if p.exists():
            text = masker.mask(extract(str(p)))
            templates.append({"template_id": f"T-{len(templates)+1}", "title": fn,
                              "claim_type": ctype, "client_id": client,
                              "is_default": False, "text": text[:1500]})
    # синтетические шаблоны под главный сценарий MVP (просрочка оплаты) — Q15/Q16
    templates.append({"template_id": "T-DEBT-DEFAULT", "title": "Претензия о просрочке оплаты (дефолт)",
                      "claim_type": "просрочка оплаты", "client_id": None, "is_default": True,
                      "text": "Претензия об уплате задолженности и неустойки по договору ТЭУ..."})
    templates.append({"template_id": "T-DEBT-ROSINKA", "title": "Претензия о просрочке — Росинка",
                      "claim_type": "просрочка оплаты", "client_id": "rosinka", "is_default": False,
                      "text": "Согласованная с клиентом Росинка форма претензии о просрочке оплаты..."})
    return templates


def build_all():
    masker = Masker()
    all_chunks: list[dict] = []
    print("=== ИНДЕКСАЦИЯ (коллекция contracts) ===")
    for m in CONTRACT_MANIFEST:
        path = BASE / m["file"]
        if not path.exists():
            print(f"  [skip] нет файла: {m['file']}")
            continue
        raw = extract(str(path))
        masked = masker.mask(raw)                      # ПДн маскируются ДО индексации
        meta = {k: v for k, v in m.items() if k not in ("file", "structured")}
        meta["source"] = m["file"]
        chunks = chunk_contract(masked, meta) if m["structured"] else chunk_generic(masked, meta)
        # если структурный чанкер не нашёл нумерацию — фолбэк
        if len(chunks) < 3:
            chunks = chunk_generic(masked, meta)
        print(f"  {m['contract_id']:22} {len(chunks):>4} чанков  ({m['file'].split('/')[-1][:45]})")
        all_chunks += chunks

    print(f"\n  Всего чанков договоров: {len(all_chunks)}")
    print(f"  Маскировано ПДн-плейсхолдеров: {len(masker.map)}")

    print("\n=== ЭМБЕДДИНГ (multilingual-e5-base) + BM25 ===")
    store = VectorStore()
    store.build(all_chunks)
    print(f"  Матрица эмбеддингов: {store.emb.shape}")

    templates = load_templates(masker)
    print(f"\n=== коллекция claim_templates: {len(templates)} шаблонов ===")
    for t in templates:
        print(f"  {t['template_id']:16} type={t['claim_type']:22} client={t['client_id']}")

    return store, templates, masker

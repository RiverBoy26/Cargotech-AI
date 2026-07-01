"""Vector store: dense (multilingual-e5) + sparse (BM25) hybrid + metadata filter.

В проде это pgvector + PostgreSQL FTS. Здесь — самодостаточная реализация на numpy,
чтобы всё гонялось локально без внешних сервисов и без GigaChat-ключа.
"""
import math
import re
import numpy as np
from sentence_transformers import SentenceTransformer

_TOKEN_RE = re.compile(r"[а-яёa-z0-9]+", re.IGNORECASE)


def tokenize(text: str) -> list[str]:
    return [t.lower() for t in _TOKEN_RE.findall(text)]


class BM25:
    def __init__(self, docs: list[list[str]], k1=1.5, b=0.75):
        self.k1, self.b = k1, b
        self.docs = docs
        self.N = len(docs)
        self.avgdl = sum(len(d) for d in docs) / max(self.N, 1)
        self.df: dict[str, int] = {}
        self.tf: list[dict[str, int]] = []
        for d in docs:
            seen = {}
            for t in d:
                seen[t] = seen.get(t, 0) + 1
            self.tf.append(seen)
            for t in set(d):
                self.df[t] = self.df.get(t, 0) + 1
        self.idf = {t: math.log(1 + (self.N - n + 0.5) / (n + 0.5)) for t, n in self.df.items()}

    def scores(self, query: str) -> np.ndarray:
        q = tokenize(query)
        out = np.zeros(self.N)
        for i, tf in enumerate(self.tf):
            dl = len(self.docs[i])
            s = 0.0
            for t in q:
                if t not in tf:
                    continue
                idf = self.idf.get(t, 0.0)
                num = tf[t] * (self.k1 + 1)
                den = tf[t] + self.k1 * (1 - self.b + self.b * dl / self.avgdl)
                s += idf * num / den
            out[i] = s
        return out


class VectorStore:
    def __init__(self, model_name="intfloat/multilingual-e5-base"):
        self.model = SentenceTransformer(model_name)
        self.chunks: list[dict] = []
        self.emb: np.ndarray | None = None
        self.bm25: BM25 | None = None

    def build(self, chunks: list[dict]):
        self.chunks = chunks
        passages = [f"passage: {c['text']}" for c in chunks]           # e5 требует префикс
        self.emb = self.model.encode(passages, normalize_embeddings=True,
                                     show_progress_bar=False, batch_size=32)
        self.bm25 = BM25([tokenize(c["text"]) for c in chunks])

    def _filter_idx(self, where: dict | None) -> np.ndarray:
        if not where:
            return np.arange(len(self.chunks))
        keep = []
        for i, c in enumerate(self.chunks):
            ok = True
            for k, v in where.items():
                cv = c.get(k)
                if isinstance(v, (list, tuple, set)):
                    ok = ok and cv in v
                else:
                    ok = ok and cv == v
            if ok:
                keep.append(i)
        return np.array(keep, dtype=int)

    def search(self, query: str, where: dict | None = None, k=5, alpha=0.6):
        """alpha — вес dense в гибриде (0..1). Возвращает [(chunk, score, dbg), ...]."""
        idx = self._filter_idx(where)
        if len(idx) == 0:
            return []
        q = self.model.encode([f"query: {query}"], normalize_embeddings=True)[0]
        dense = self.emb[idx] @ q                                       # косинус (эмбеддинги норм.)
        sparse = self.bm25.scores(query)[idx]
        dn = _minmax(dense)
        sn = _minmax(sparse)
        hybrid = alpha * dn + (1 - alpha) * sn
        order = np.argsort(-hybrid)[:k]
        res = []
        for o in order:
            gi = idx[o]
            res.append((self.chunks[gi], float(hybrid[o]),
                        {"dense": float(dn[o]), "bm25": float(sn[o])}))
        return res


def _minmax(x: np.ndarray) -> np.ndarray:
    if len(x) == 0:
        return x
    lo, hi = x.min(), x.max()
    if hi - lo < 1e-9:
        return np.zeros_like(x)
    return (x - lo) / (hi - lo)

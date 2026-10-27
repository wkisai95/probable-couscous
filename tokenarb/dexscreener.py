"""Prezzi on-chain dalle pool token/USDC su Base tramite DexScreener.

API pubblica senza chiave, ~300 richieste/minuto, fino a 30 indirizzi per richiesta:
  GET https://api.dexscreener.com/tokens/v1/base/{addr1,addr2,...}
Per ogni token si sceglie la pool token/USDC su Aero (Aerodrome) con più liquidità;
solo se non esiste si ripiega sulla pool token/USDC più liquida di un altro DEX.
"""
from __future__ import annotations

import logging
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Tuple

import requests

log = logging.getLogger(__name__)
API = "https://api.dexscreener.com"
USDC = "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913"
HEADERS = {"Accept": "application/json", "User-Agent": "tokenarb-scanner/1.0"}


@dataclass
class DexPool:
    token: str            # indirizzo del token (minuscolo)
    pair: str
    dex: str              # dexId + etichette, es. "aerodrome v3"
    price_usd: float      # prezzo del token in USD nella pool
    liquidity_usd: float
    other: str            # indirizzo dell'altro token della pool (minuscolo)
    url: str = ""

    @property
    def is_aero(self) -> bool:
        return "aero" in self.dex.lower()

    @property
    def is_usdc(self) -> bool:
        return self.other == USDC


@dataclass
class FetchStats:
    requests: int = 0
    failed: int = 0
    seconds: float = 0.0
    pairs: int = 0
    dex_ids: Dict[str, int] = field(default_factory=dict)


def parse_pairs(pairs: Iterable[dict], wanted: Iterable[str]) -> List[DexPool]:
    """Converte le pair DexScreener in DexPool dal punto di vista dei token richiesti."""
    want = {w.lower() for w in wanted}
    out: List[DexPool] = []
    for p in pairs:
        if p.get("chainId") != "base":
            continue
        base = ((p.get("baseToken") or {}).get("address") or "").lower()
        quote = ((p.get("quoteToken") or {}).get("address") or "").lower()
        if base in want:
            token, other = base, quote
        elif quote in want:
            token, other = quote, base
        else:
            continue
        try:
            price_usd = float(p.get("priceUsd") or 0)
            price_native = float(p.get("priceNative") or 0)
        except (TypeError, ValueError):
            continue
        if token == base:
            px = price_usd
        else:
            # priceUsd/priceNative sono riferiti al token base: prezzo del quote = USD(base) / (quote per base)
            px = price_usd / price_native if price_native > 0 else 0.0
        if px <= 0:
            continue
        labels = " ".join(p.get("labels") or [])
        out.append(DexPool(
            token=token, pair=p.get("pairAddress", ""),
            dex=f"{p.get('dexId', '?')} {labels}".strip(), price_usd=px,
            liquidity_usd=float((p.get("liquidity") or {}).get("usd") or 0), other=other,
            url=p.get("url", ""),
        ))
    return out


def best_usdc_pool(pools: Iterable[DexPool]) -> Optional[DexPool]:
    """Pool token/USDC preferita: Aero se esiste, poi la più liquida."""
    usdc = [p for p in pools if p.is_usdc]
    if not usdc:
        return None
    return max(usdc, key=lambda p: (p.is_aero, p.liquidity_usd))


def _get(session: requests.Session, url: str) -> list:
    for attempt in range(3):
        r = session.get(url, headers=HEADERS, timeout=20)
        if r.status_code == 429 and attempt < 2:
            time.sleep(1.0 + attempt)
            continue
        r.raise_for_status()
        data = r.json()
        return data if isinstance(data, list) else data.get("pairs") or []
    return []


def fetch_pools(addresses: List[str], chunk: int = 30, workers: int = 8
                ) -> Tuple[Dict[str, List[DexPool]], FetchStats]:
    """Tutte le pool su Base dei token richiesti, con richieste parallele a blocchi di `chunk`."""
    stats = FetchStats()
    chunks = [addresses[i:i + chunk] for i in range(0, len(addresses), chunk)]
    by_token: Dict[str, List[DexPool]] = {a.lower(): [] for a in addresses}
    session = requests.Session()
    t0 = time.perf_counter()

    def job(c: List[str]):
        return c, _get(session, f"{API}/tokens/v1/base/{','.join(c)}")

    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(chunks)))) as ex:
        futures = [ex.submit(job, c) for c in chunks]
        for f in futures:
            stats.requests += 1
            try:
                c, pairs = f.result()
            except Exception as e:  # noqa: BLE001
                stats.failed += 1
                log.warning("DexScreener: %s", e)
                continue
            stats.pairs += len(pairs)
            for p in pairs:
                d = p.get("dexId", "?")
                stats.dex_ids[d] = stats.dex_ids.get(d, 0) + 1
            for pool in parse_pairs(pairs, c):
                by_token.setdefault(pool.token, []).append(pool)
    stats.seconds = time.perf_counter() - t0
    return by_token, stats


def fetch_prices(addresses: List[str], chunk: int = 30, workers: int = 8
                 ) -> Tuple[Dict[str, DexPool], FetchStats]:
    """Prezzo di ogni token dalla sua pool token/USDC preferita (Aero)."""
    by_token, stats = fetch_pools(addresses, chunk, workers)
    out = {}
    for token, pools in by_token.items():
        best = best_usdc_pool(pools)
        if best:
            out[token] = best
    return out, stats

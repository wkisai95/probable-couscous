"""Diagnostica: in quali pool DEX su Base è quotato ogni token (fonte GeckoTerminal).

Serve a capire dove si trova la liquidità (Aerodrome/Aero, Uniswap, ...) e
perché un token non viene instradato dall'aggregatore. Non usato nella scansione.
API pubblica, ~30 richieste/minuto: https://www.geckoterminal.com/dex-api
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Dict, List

import requests

log = logging.getLogger(__name__)
API = "https://api.geckoterminal.com/api/v2/networks/base"
HEADERS = {"Accept": "application/json;version=20230302", "User-Agent": "tokenarb-scanner/1.0"}


@dataclass
class PoolInfo:
    address: str
    name: str
    dex: str
    reserve_usd: float
    volume_24h_usd: float


@dataclass
class TokenLiquidity:
    address: str
    pools: List[PoolInfo] = field(default_factory=list)

    @property
    def total_usd(self) -> float:
        return sum(p.reserve_usd for p in self.pools)

    def dexes(self) -> List[str]:
        return sorted({p.dex for p in self.pools})


def _get(url: str, params: dict | None = None) -> dict:
    for attempt in range(4):
        r = requests.get(url, params=params, headers=HEADERS, timeout=30)
        if r.status_code == 429:  # rate limit: attesa crescente
            time.sleep(15 * (attempt + 1))
            continue
        r.raise_for_status()
        time.sleep(2.2)  # resta sotto ~30 richieste/minuto
        return r.json()
    r.raise_for_status()
    return {}


def _pool(obj: dict) -> PoolInfo:
    a = obj.get("attributes", {})
    dex = (obj.get("relationships", {}).get("dex", {}).get("data") or {}).get("id", "?")
    return PoolInfo(
        address=a.get("address", ""),
        name=a.get("name", ""),
        dex=dex,
        reserve_usd=float(a.get("reserve_in_usd") or 0),
        volume_24h_usd=float((a.get("volume_usd") or {}).get("h24") or 0),
    )


def token_pools(addresses: List[str]) -> Dict[str, TokenLiquidity]:
    """Pool di ogni token: una richiesta ogni 30 token (top pool), poi dettaglio dove serve."""
    out: Dict[str, TokenLiquidity] = {a.lower(): TokenLiquidity(a) for a in addresses}
    for i in range(0, len(addresses), 30):
        chunk = addresses[i:i + 30]
        try:
            data = _get(f"{API}/tokens/multi/{','.join(chunk)}", {"include": "top_pools"})
        except Exception as e:  # noqa: BLE001
            log.warning("GeckoTerminal multi: %s", e)
            continue
        pools = {p["id"]: _pool(p) for p in data.get("included", []) if p.get("type") == "pool"}
        for tok in data.get("data", []):
            addr = tok.get("attributes", {}).get("address", "").lower()
            refs = tok.get("relationships", {}).get("top_pools", {}).get("data", []) or []
            if addr in out:
                out[addr].pools = [pools[r["id"]] for r in refs if r.get("id") in pools]
    return out

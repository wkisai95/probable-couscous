"""Lista ufficiale dei token azionari Coinbase su Base.

Fonte: API pubblica Coinbase (sola lettura, senza chiave)
https://api.coinbase.com/v1/tokenized-stocks
I token vanno identificati per indirizzo: solo quelli restituiti qui sono emessi da Coinbase.
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import List, Optional

import requests

from .models import CbToken

log = logging.getLogger(__name__)
API = "https://api.coinbase.com/v1/tokenized-stocks"


def _dt(s: Optional[str]) -> Optional[datetime]:
    if not s:
        return None
    try:
        return datetime.fromisoformat(s.replace("Z", "+00:00"))
    except ValueError:
        return None


def ticker_from_symbol(symbol: str) -> str:
    """AAPLc -> AAPL (suffisso 'c' dei token Coinbase)."""
    return symbol[:-1] if symbol.endswith("c") else symbol


def parse_token(t: dict) -> CbToken:
    sym = t.get("symbol", "")
    return CbToken(
        address=t["contract_address"],
        symbol=sym,
        ticker=ticker_from_symbol(sym),
        name=t.get("name", sym),
        decimals=int(t.get("decimals", 18)),
        multiplier=float(t.get("multiplier") or 1.0),
        nav_price=float(t["nav_price"]) if t.get("nav_price") else None,
        nav_updated_at=_dt(t.get("nav_price_updated_at")),
        paused_features=list(t.get("paused_features") or []),
    )


def list_tokens(session: Optional[requests.Session] = None) -> List[CbToken]:
    s = session or requests.Session()
    tokens: List[CbToken] = []
    page_token = None
    for _ in range(50):  # paginazione: leggere tutte le pagine
        params = {"page_size": 500}
        if page_token:
            params["page_token"] = page_token
        r = s.get(API, params=params, timeout=20)
        r.raise_for_status()
        data = r.json()
        tokens += [parse_token(t) for t in data.get("tokens", [])]
        page_token = data.get("next_page_token")
        if not page_token:
            break
    log.info("Token Coinbase su Base: %s", ", ".join(t.symbol for t in tokens))
    return tokens

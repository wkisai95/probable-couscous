"""Prezzi di riferimento: Wall Street (Yahoo/Finnhub) e feed Chainlink su Base."""
from __future__ import annotations

import logging
import os
from datetime import datetime, timezone
from typing import Dict, List

import requests

from .models import RefQuote

log = logging.getLogger(__name__)
LATEST_ROUND_DATA = "0xfeaf968c"  # selector di latestRoundData()


def fetch_yahoo(symbols: Dict[str, str]) -> Dict[str, RefQuote]:
    """symbols: {ticker: simbolo_yahoo}. Ultimo prezzo a 1 min con pre/after-market."""
    import yfinance as yf

    ysyms = list(symbols.values())
    out: Dict[str, RefQuote] = {}
    df = None
    for period in ("1d", "5d"):  # a mercato chiuso '1d' può essere vuoto
        df = yf.download(ysyms, period=period, interval="1m", prepost=True,
                         group_by="ticker", progress=False, auto_adjust=False)
        if df is not None and not df.empty:
            break
    if df is None or df.empty:
        return out
    for t, ys in symbols.items():
        try:
            sub = df[ys] if len(ysyms) > 1 else df
            closes = sub["Close"].dropna()
            if hasattr(closes, "columns"):
                closes = closes.iloc[:, 0]
            if closes.empty:
                continue
            ts = closes.index[-1].to_pydatetime()
            if ts.tzinfo is None:
                ts = ts.replace(tzinfo=timezone.utc)
            out[t] = RefQuote(t, float(closes.iloc[-1]), ts, "yahoo")
        except Exception as e:  # noqa: BLE001
            log.warning("Yahoo %s: %s", ys, e)
    return out


def fetch_finnhub(symbols: Dict[str, str]) -> Dict[str, RefQuote]:
    key = os.environ.get("FINNHUB_API_KEY")
    if not key:
        raise RuntimeError("FINNHUB_API_KEY mancante")
    out: Dict[str, RefQuote] = {}
    for t, s in symbols.items():
        try:
            r = requests.get("https://finnhub.io/api/v1/quote",
                             params={"symbol": s, "token": key}, timeout=15)
            r.raise_for_status()
            q = r.json()
            if q.get("c"):
                out[t] = RefQuote(t, float(q["c"]),
                                  datetime.fromtimestamp(q["t"], tz=timezone.utc), "finnhub")
        except Exception as e:  # noqa: BLE001
            log.warning("Finnhub %s: %s", s, e)
    return out


def fetch_wallstreet(symbols: Dict[str, str], source: str) -> Dict[str, RefQuote]:
    try:
        return fetch_finnhub(symbols) if source == "finnhub" else fetch_yahoo(symbols)
    except Exception as e:  # noqa: BLE001
        log.error("Prezzi Wall Street non disponibili: %s", e)
        return {}


def fetch_chainlink(feeds: Dict[str, str], rpc_url: str) -> Dict[str, RefQuote]:
    """Legge latestRoundData() dai feed Chainlink 'Base Tokenized Equities'.

    Il feed riporta già il prezzo per token (azione × multiplier), 8 decimali.
    Fuori orario il valore resta fermo e updatedAt non avanza.
    """
    out: Dict[str, RefQuote] = {}
    if not feeds:
        return out
    batch = [{"jsonrpc": "2.0", "id": i, "method": "eth_call",
              "params": [{"to": addr, "data": LATEST_ROUND_DATA}, "latest"]}
             for i, addr in enumerate(feeds.values())]
    try:
        r = requests.post(rpc_url, json=batch, timeout=20)
        r.raise_for_status()
        res = {x["id"]: x.get("result") for x in r.json()}
    except Exception as e:  # noqa: BLE001
        log.warning("Chainlink RPC: %s", e)
        return out
    for i, t in enumerate(feeds):
        raw = res.get(i)
        if not raw or len(raw) < 2 + 64 * 5:
            continue
        words = [raw[2 + 64 * k: 2 + 64 * (k + 1)] for k in range(5)]
        answer = int(words[1], 16)
        if answer >= 2 ** 255:
            answer -= 2 ** 256
        updated = datetime.fromtimestamp(int(words[3], 16), tz=timezone.utc)
        out[t] = RefQuote(t, answer / 1e8, updated, "chainlink", per_token=True)
    return out

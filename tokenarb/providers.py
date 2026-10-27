"""Fonti prezzi: venue di stock tokenizzati (order book) e prezzi Wall Street."""
from __future__ import annotations

import logging
import os
from datetime import datetime, timezone
from typing import Dict, List, Optional

import requests

from .models import OrderBook, RefQuote, TokenQuote

log = logging.getLogger(__name__)
UA = {"User-Agent": "tokenarb-scanner/1.0"}
TIMEOUT = 15


def _now() -> datetime:
    return datetime.now(timezone.utc)


# --------------------------------------------------------------------------- #
# Venue tokenizzati
# --------------------------------------------------------------------------- #
class Venue:
    name = "base"

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.depth = int(cfg.get("depth", 50))
        self.symbol_format = cfg.get("symbol_format", "{ticker}")
        self.overrides: Dict[str, str] = cfg.get("symbol_overrides", {}) or {}

    def symbol(self, ticker: str) -> str:
        return self.overrides.get(ticker, self.symbol_format.format(ticker=ticker))

    def fetch(self, ticker: str) -> Optional[TokenQuote]:
        raise NotImplementedError


class KrakenVenue(Venue):
    """xStocks su Kraken (es. AAPLxUSD). Prezzi già in USD."""
    name = "kraken"
    URL = "https://api.kraken.com/0/public/Depth"

    def fetch(self, ticker: str) -> Optional[TokenQuote]:
        sym = self.symbol(ticker)
        r = requests.get(self.URL, params={"pair": sym, "count": self.depth,
                                           "asset_class": "tokenized_asset"},
                         headers=UA, timeout=TIMEOUT)
        r.raise_for_status()
        data = r.json()
        if data.get("error"):
            raise RuntimeError(f"Kraken {sym}: {data['error']}")
        book_raw = next(iter(data["result"].values()))
        book = OrderBook(
            bids=[(float(p), float(q)) for p, q, *_ in book_raw["bids"]],
            asks=[(float(p), float(q)) for p, q, *_ in book_raw["asks"]],
        )
        return TokenQuote(self.name, ticker, sym, book, _now())


class BybitVenue(Venue):
    """xStocks su Bybit spot (es. AAPLXUSDT). Prezzi in USDT -> convertiti in USD."""
    name = "bybit"
    URL = "https://api.bybit.com/v5/market/orderbook"

    def __init__(self, cfg: dict, usdt_usd: float = 1.0):
        super().__init__(cfg)
        self.usdt_usd = usdt_usd

    def fetch(self, ticker: str) -> Optional[TokenQuote]:
        sym = self.symbol(ticker)
        r = requests.get(self.URL, params={"category": "spot", "symbol": sym,
                                           "limit": min(self.depth, 200)},
                         headers=UA, timeout=TIMEOUT)
        r.raise_for_status()
        data = r.json()
        if data.get("retCode") != 0:
            raise RuntimeError(f"Bybit {sym}: {data.get('retMsg')}")
        res = data["result"]
        book = OrderBook(
            bids=[(float(p), float(q)) for p, q in res.get("b", [])],
            asks=[(float(p), float(q)) for p, q in res.get("a", [])],
        ).scaled(self.usdt_usd)
        return TokenQuote(self.name, ticker, sym, book, _now())


def fetch_usdt_usd() -> float:
    """Cambio USDT/USD da Kraken (fallback 1.0)."""
    try:
        r = requests.get("https://api.kraken.com/0/public/Ticker",
                         params={"pair": "USDTUSD"}, headers=UA, timeout=TIMEOUT)
        r.raise_for_status()
        t = next(iter(r.json()["result"].values()))
        return (float(t["a"][0]) + float(t["b"][0])) / 2
    except Exception as e:  # noqa: BLE001
        log.warning("USDT/USD non disponibile (%s), uso 1.0", e)
        return 1.0


def build_venues(cfg: dict) -> List[Venue]:
    venues: List[Venue] = []
    vcfg = cfg.get("venues", {})
    usdt = None
    disabled = {x.strip() for x in os.getenv("TOKENARB_DISABLE_VENUES", "").split(",") if x.strip()}
    for name, c in vcfg.items():
        if not c.get("enabled", True) or name in disabled:
            continue
        if name == "kraken":
            venues.append(KrakenVenue(c))
        elif name == "bybit":
            if usdt is None:
                usdt = fetch_usdt_usd()
            venues.append(BybitVenue(c, usdt))
        else:
            log.warning("Venue sconosciuto: %s", name)
    return venues


# --------------------------------------------------------------------------- #
# Prezzi Wall Street
# --------------------------------------------------------------------------- #
def fetch_reference_yahoo(tickers: List[str]) -> Dict[str, RefQuote]:
    """Ultimo prezzo a 1 minuto incluso pre/after-market (yfinance, gratuito)."""
    import yfinance as yf

    out: Dict[str, RefQuote] = {}
    df = yf.download(tickers, period="1d", interval="1m", prepost=True,
                     group_by="ticker", progress=False, auto_adjust=False,
                     threads=True)
    if df is None or df.empty:
        # fuori orario 'period=1d' può essere vuoto: ripiega su 5 giorni
        df = yf.download(tickers, period="5d", interval="1m", prepost=True,
                         group_by="ticker", progress=False, auto_adjust=False,
                         threads=True)
    for t in tickers:
        try:
            sub = df[t] if len(tickers) > 1 else df
            closes = sub["Close"].dropna()
            if hasattr(closes, "columns"):  # MultiIndex residuo
                closes = closes.iloc[:, 0]
            if closes.empty:
                continue
            ts = closes.index[-1].to_pydatetime()
            if ts.tzinfo is None:
                ts = ts.replace(tzinfo=timezone.utc)
            out[t] = RefQuote(t, float(closes.iloc[-1]), ts, "yahoo")
        except Exception as e:  # noqa: BLE001
            log.warning("Yahoo %s: %s", t, e)
    return out


def fetch_reference_finnhub(tickers: List[str]) -> Dict[str, RefQuote]:
    """Finnhub /quote (richiede FINNHUB_API_KEY, solo sessione regolare)."""
    key = os.environ.get("FINNHUB_API_KEY")
    if not key:
        raise RuntimeError("FINNHUB_API_KEY mancante")
    out: Dict[str, RefQuote] = {}
    for t in tickers:
        try:
            r = requests.get("https://finnhub.io/api/v1/quote",
                             params={"symbol": t, "token": key}, timeout=TIMEOUT)
            r.raise_for_status()
            q = r.json()
            if q.get("c"):
                ts = datetime.fromtimestamp(q["t"], tz=timezone.utc)
                out[t] = RefQuote(t, float(q["c"]), ts, "finnhub")
        except Exception as e:  # noqa: BLE001
            log.warning("Finnhub %s: %s", t, e)
    return out


def fetch_reference(tickers: List[str], source: str) -> Dict[str, RefQuote]:
    if source == "finnhub":
        return fetch_reference_finnhub(tickers)
    return fetch_reference_yahoo(tickers)

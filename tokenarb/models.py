"""Strutture dati condivise."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import List, Optional, Tuple

Level = Tuple[float, float]  # (prezzo, quantità)


@dataclass
class OrderBook:
    bids: List[Level] = field(default_factory=list)  # ordinati dal prezzo più alto
    asks: List[Level] = field(default_factory=list)  # ordinati dal prezzo più basso

    @property
    def best_bid(self) -> Optional[float]:
        return self.bids[0][0] if self.bids else None

    @property
    def best_ask(self) -> Optional[float]:
        return self.asks[0][0] if self.asks else None

    @property
    def mid(self) -> Optional[float]:
        if self.best_bid is None or self.best_ask is None:
            return None
        return (self.best_bid + self.best_ask) / 2

    def scaled(self, price_factor: float) -> "OrderBook":
        """Converte i prezzi (es. USDT -> USD)."""
        return OrderBook(
            bids=[(p * price_factor, q) for p, q in self.bids],
            asks=[(p * price_factor, q) for p, q in self.asks],
        )


@dataclass
class TokenQuote:
    venue: str
    ticker: str          # ticker azionario sottostante, es. AAPL
    symbol: str          # simbolo sul venue, es. AAPLxUSD
    book: OrderBook
    fetched_at: datetime


@dataclass
class RefQuote:
    ticker: str
    price: float
    as_of: datetime      # timestamp dell'ultimo prezzo
    source: str


@dataclass
class SizingResult:
    direction: str             # "buy_token_sell_stock" | "sell_token_buy_stock"
    qty: float                 # numero di token (= azioni * moltiplicatore)
    notional_usd: float
    token_vwap: float
    stock_price: float         # prezzo di esecuzione stimato lato azione (per token)
    gross_profit_usd: float
    fees_usd: float
    net_profit_usd: float
    net_bps: float
    levels_used: int


@dataclass
class Opportunity:
    ticker: str
    venue: str
    symbol: str
    session: str               # regular | pre | post | closed
    ref_price: float           # prezzo Wall Street (per token, già moltiplicato)
    ref_source: str
    ref_age_min: float
    token_bid: float
    token_ask: float
    token_mid: float
    gap_bps: float             # (mid token - ref) / ref in bps, segno = premio/sconto
    best: Optional[SizingResult]       # size ottimale
    fixed: Optional[SizingResult]      # size configurata (trade_size_usd)
    actionable: bool           # mercato aperto, dato fresco, soglie superate
    notes: List[str] = field(default_factory=list)

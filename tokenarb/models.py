"""Strutture dati condivise."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import List, Optional, Tuple


@dataclass
class CbToken:
    """Stock tokenizzato emesso da Coinbase (standard B20) su Base."""
    address: str
    symbol: str          # es. AAPLc
    ticker: str          # es. AAPL
    name: str
    decimals: int
    multiplier: float    # azioni per token (dividendi/split), 1.0 al lancio
    nav_price: Optional[float] = None
    nav_updated_at: Optional[datetime] = None
    paused_features: List[int] = field(default_factory=list)


@dataclass
class DexQuote:
    amount_in: float     # unità umane (USDC o token)
    amount_out: float
    gas_usd: float       # gas L2 + fee L1


@dataclass
class RefQuote:
    ticker: str
    price: float         # prezzo per azione (yahoo/finnhub) o per token (chainlink)
    as_of: datetime
    source: str
    per_token: bool = False   # True se già moltiplicato (feed Chainlink)


@dataclass
class SizingResult:
    direction: str             # "buy_token_sell_stock" | "sell_token_buy_stock"
    notional_usd: float        # USDC spesi (buy) o controvalore token venduti (sell)
    tokens: float
    exec_price: float          # prezzo medio on-chain per token (fee DEX incluse)
    fair_price: float          # prezzo Borsa per token (azione × multiplier)
    gross_profit_usd: float
    costs_usd: float           # gas + costi lato azione
    net_profit_usd: float
    net_bps: float
    ladder: List[Tuple[float, float]] = field(default_factory=list)  # (size $, netto $)


@dataclass
class Opportunity:
    ticker: str
    symbol: str
    address: str
    session: str
    fair_price: float          # per token
    ref_source: str
    ref_age_min: float
    dex_buy_price: float       # prezzo di acquisto on-chain per piccola size
    dex_sell_price: float      # prezzo di vendita on-chain per piccola size
    gap_bps: float             # mid on-chain vs Borsa
    chainlink_price: Optional[float]
    best: Optional[SizingResult]
    fixed: Optional[SizingResult]
    actionable: bool
    notes: List[str] = field(default_factory=list)

"""Calcolo della size e del profitto netto.

Logica: si compra il lato economico e si vende quello caro, in coppia
(arbitraggio coperto). Lato token si "cammina" l'order book reale del venue;
lato azione si assume liquidità ampia al prezzo di riferimento +/- metà spread
stimato (configurabile, più largo in pre/after-market).

Il profitto in funzione della quantità è concavo e lineare a tratti (ogni
livello del book peggiora il prezzo), quindi il massimo cade su un confine
di livello o sul limite di capitale: basta valutare quei punti.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

from .models import Level, OrderBook, SizingResult

BUY_TOKEN = "buy_token_sell_stock"
SELL_TOKEN = "sell_token_buy_stock"


@dataclass
class CostModel:
    token_fee_bps: float = 25.0        # fee taker sul venue del token
    stock_fee_bps: float = 0.0         # commissione % broker azioni
    stock_fee_per_share: float = 0.0   # commissione fissa per azione
    stock_half_spread_bps: float = 2.0 # metà spread stimato lato azione
    fixed_cost_usd: float = 0.0        # costi fissi per operazione (es. prelievo, gas)
    shares_per_token: float = 1.0      # azioni rappresentate da 1 token


def _fill(levels: List[Level], qty: float) -> tuple[float, int]:
    """Costo totale per riempire `qty` camminando i livelli. -> (costo, livelli usati)."""
    remaining, cost, used = qty, 0.0, 0
    for price, size in levels:
        if remaining <= 1e-12:
            break
        take = min(size, remaining)
        cost += take * price
        remaining -= take
        used += 1
    if remaining > 1e-9:
        raise ValueError("liquidità insufficiente")
    return cost, used


def evaluate(book: OrderBook, direction: str, ref_price_per_token: float,
             qty: float, cm: CostModel) -> Optional[SizingResult]:
    """Profitto netto per una quantità precisa di token."""
    if qty <= 0:
        return None
    levels = book.asks if direction == BUY_TOKEN else book.bids
    try:
        token_value, used = _fill(levels, qty)
    except ValueError:
        return None

    hs = cm.stock_half_spread_bps / 1e4
    if direction == BUY_TOKEN:
        stock_px = ref_price_per_token * (1 - hs)   # vendiamo (short) l'azione al bid
        gross = qty * stock_px - token_value
    else:
        stock_px = ref_price_per_token * (1 + hs)   # compriamo l'azione all'ask
        gross = token_value - qty * stock_px

    stock_notional = qty * stock_px
    shares = qty * cm.shares_per_token
    fees = (token_value * cm.token_fee_bps / 1e4
            + stock_notional * cm.stock_fee_bps / 1e4
            + shares * cm.stock_fee_per_share
            + cm.fixed_cost_usd)
    net = gross - fees
    return SizingResult(
        direction=direction,
        qty=qty,
        notional_usd=token_value,
        token_vwap=token_value / qty,
        stock_price=stock_px,
        gross_profit_usd=gross,
        fees_usd=fees,
        net_profit_usd=net,
        net_bps=net / token_value * 1e4 if token_value else 0.0,
        levels_used=used,
    )


def _qty_for_notional(levels: List[Level], notional: float) -> float:
    """Quantità acquistabile/vendibile con un controvalore dato."""
    qty, remaining = 0.0, notional
    for price, size in levels:
        lvl_val = price * size
        if lvl_val >= remaining:
            return qty + remaining / price
        qty += size
        remaining -= lvl_val
    return qty  # book esaurito


def optimal(book: OrderBook, direction: str, ref_price_per_token: float,
            cm: CostModel, max_notional_usd: float) -> Optional[SizingResult]:
    """Size che massimizza il profitto netto entro il capitale massimo."""
    levels = book.asks if direction == BUY_TOKEN else book.bids
    if not levels:
        return None
    cap_qty = _qty_for_notional(levels, max_notional_usd)
    candidates, cum = [], 0.0
    for _, size in levels:
        cum += size
        if cum >= cap_qty:
            break
        candidates.append(cum)
    candidates.append(cap_qty)

    best = None
    for q in candidates:
        r = evaluate(book, direction, ref_price_per_token, q, cm)
        if r and (best is None or r.net_profit_usd > best.net_profit_usd):
            best = r
    return best


def fixed_size(book: OrderBook, direction: str, ref_price_per_token: float,
               cm: CostModel, notional_usd: float) -> Optional[SizingResult]:
    levels = book.asks if direction == BUY_TOKEN else book.bids
    if not levels:
        return None
    qty = _qty_for_notional(levels, notional_usd)
    return evaluate(book, direction, ref_price_per_token, qty, cm)


def direction_for(book: OrderBook, ref_price_per_token: float) -> Optional[str]:
    """Token sotto il prezzo di Borsa -> compra token; sopra -> vendi token."""
    if book.best_ask is not None and book.best_ask < ref_price_per_token:
        return BUY_TOKEN
    if book.best_bid is not None and book.best_bid > ref_price_per_token:
        return SELL_TOKEN
    return None

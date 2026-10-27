"""Size ottimale e profitto netto con quote on-chain reali.

Strategia (arbitraggio coperto):
  token sotto prezzo  -> compra token con USDC su Base  + vendi (short) l'azione
  token sopra prezzo  -> vendi token per USDC su Base   + compra l'azione

Su un AMM il prezzo medio peggiora con la size, quindi il profitto netto sale,
raggiunge un massimo e poi scende. Si chiedono quote all'aggregatore su una
scala di size crescenti, ci si ferma quando il profitto cala e si raffina
attorno al massimo.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, List, Optional

from .models import DexQuote, SizingResult

BUY_TOKEN = "buy_token_sell_stock"
SELL_TOKEN = "sell_token_buy_stock"

# quote_fn(direction, amount_in) -> DexQuote
#   BUY_TOKEN : amount_in in USDC, amount_out in token
#   SELL_TOKEN: amount_in in token, amount_out in USDC
QuoteFn = Callable[[str, float], DexQuote]


@dataclass
class CostModel:
    stock_fee_bps: float = 0.0
    stock_fee_per_share: float = 0.0
    stock_half_spread_bps: float = 2.0
    fixed_cost_usd: float = 0.0
    multiplier: float = 1.0          # azioni per token


def evaluate(direction: str, notional: float, fair: float, quote_fn: QuoteFn,
             cm: CostModel) -> Optional[SizingResult]:
    """`notional` in USD; `fair` = prezzo Borsa per token."""
    hs = cm.stock_half_spread_bps / 1e4
    try:
        if direction == BUY_TOKEN:
            q = quote_fn(BUY_TOKEN, notional)
            tokens = q.amount_out
            if tokens <= 0:
                return None
            spent = notional
            stock_px = fair * (1 - hs)            # short dell'azione al bid
            gross = tokens * stock_px - spent
            exec_px = spent / tokens
        else:
            tokens = notional / fair
            q = quote_fn(SELL_TOKEN, tokens)
            received = q.amount_out
            stock_px = fair * (1 + hs)            # acquisto dell'azione all'ask
            gross = received - tokens * stock_px
            exec_px = received / tokens
    except Exception:  # noqa: BLE001 - quote fallita = size non eseguibile
        return None

    shares = tokens * cm.multiplier
    costs = (q.gas_usd + tokens * stock_px * cm.stock_fee_bps / 1e4
             + shares * cm.stock_fee_per_share + cm.fixed_cost_usd)
    net = gross - costs
    value = tokens * exec_px
    return SizingResult(direction=direction, notional_usd=value, tokens=tokens,
                        exec_price=exec_px, fair_price=fair, gross_profit_usd=gross,
                        costs_usd=costs, net_profit_usd=net,
                        net_bps=net / value * 1e4 if value else 0.0)


def optimal(direction: str, fair: float, quote_fn: QuoteFn, cm: CostModel,
            ladder: List[float], max_notional: float) -> Optional[SizingResult]:
    sizes = sorted({s for s in ladder if s <= max_notional} | {max_notional})
    results: List[SizingResult] = []
    for s in sizes:
        r = evaluate(direction, s, fair, quote_fn, cm)
        if r is None:
            break
        results.append(r)
        # concavo: due cali consecutivi -> il massimo è alle spalle
        if len(results) >= 3 and results[-1].net_profit_usd < results[-2].net_profit_usd \
                < results[-3].net_profit_usd:
            break
    if not results:
        return None
    i = max(range(len(results)), key=lambda k: results[k].net_profit_usd)
    best = results[i]
    # raffinamento: punti medi verso i vicini
    for j in (i - 1, i + 1):
        if 0 <= j < len(results):
            mid = (sizes[i] + sizes[j]) / 2
            r = evaluate(direction, mid, fair, quote_fn, cm)
            if r and r.net_profit_usd > best.net_profit_usd:
                best = r
    best.ladder = [(round(r.notional_usd, 2), round(r.net_profit_usd, 2)) for r in results]
    return best


def direction_for(buy_px: float, sell_px: float, fair: float) -> Optional[str]:
    """Prezzi on-chain per piccola size vs prezzo Borsa per token."""
    if buy_px < fair:
        return BUY_TOKEN
    if sell_px > fair:
        return SELL_TOKEN
    return None

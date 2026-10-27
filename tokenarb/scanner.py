"""Ciclo di scansione: token Coinbase su Base vs Wall Street."""
from __future__ import annotations

import csv
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Dict, List, Optional

from . import sizing
from .coinbase import list_tokens
from .dex import KyberSwap, NoRouteError
from .market_hours import DEFAULT_HOLIDAYS, session_at
from .models import CbToken, Opportunity, RefQuote
from .reference import fetch_chainlink, fetch_wallstreet

log = logging.getLogger(__name__)

CSV_FIELDS = [
    "time_utc", "ticker", "symbol", "session", "fair_price", "ref_source", "ref_age_min",
    "chainlink_price", "dex_buy", "dex_sell", "gap_bps", "direction", "opt_size_usd",
    "opt_tokens", "opt_net_profit_usd", "opt_net_bps", "fixed_size_usd",
    "fixed_net_profit_usd", "actionable", "notes",
]


def cost_model(cfg: dict, session: str, multiplier: float) -> sizing.CostModel:
    s = cfg.get("stock_costs", {})
    hs = s.get("half_spread_bps_regular", 2.0) if session == "regular" \
        else s.get("half_spread_bps_extended", 15.0)
    return sizing.CostModel(
        stock_fee_bps=s.get("fee_bps", 0.0),
        stock_fee_per_share=s.get("fee_per_share", 0.0),
        stock_half_spread_bps=hs,
        fixed_cost_usd=s.get("fixed_cost_usd", 0.0),
        multiplier=multiplier,
    )


def make_quote_fn(dex, token: CbToken, usdc: dict) -> sizing.QuoteFn:
    def q(direction: str, amount_in: float):
        if direction == sizing.BUY_TOKEN:
            return dex.quote(usdc["address"], usdc["decimals"],
                             token.address, token.decimals, amount_in)
        return dex.quote(token.address, token.decimals,
                         usdc["address"], usdc["decimals"], amount_in)
    return q


def yahoo_symbol(ticker: str, overrides: Dict[str, str]) -> str:
    """BRK.B -> BRK-B (formato Yahoo), salvo override in config."""
    return overrides.get(ticker, ticker.replace(".", "-").replace("/", "-"))


def _age_min(now: datetime, ts: datetime) -> float:
    return (now - ts).total_seconds() / 60


def pick_fair(t: CbToken, ws: Optional[RefQuote], cl: Optional[RefQuote],
              now: datetime, max_age: float) -> Optional[tuple]:
    """-> (prezzo per token, fonte, età min). Priorità: Wall Street fresco, poi Chainlink."""
    if ws and _age_min(now, ws.as_of) <= max_age:
        return ws.price * t.multiplier, ws.source, _age_min(now, ws.as_of)
    if cl and _age_min(now, cl.as_of) <= max_age:
        return cl.price, cl.source, _age_min(now, cl.as_of)
    if ws:  # vecchio ma meglio di niente, verrà marcato come non operabile
        return ws.price * t.multiplier, ws.source, _age_min(now, ws.as_of)
    if cl:
        return cl.price, cl.source, _age_min(now, cl.as_of)
    return None


def scan(cfg: dict,
         tokens_fn: Callable[[], List[CbToken]] = list_tokens,
         wallstreet_fn: Callable = fetch_wallstreet,
         chainlink_fn: Callable = fetch_chainlink,
         dex=None) -> List[Opportunity]:
    now = datetime.now(timezone.utc)
    session = session_at(now, set(cfg.get("holidays", [])) | DEFAULT_HOLIDAYS)
    th = cfg.get("thresholds", {})
    tradable = set(cfg.get("tradable_sessions", ["regular"]))
    max_age = th.get("max_ref_age_minutes", 20)
    usdc = cfg["base"]["usdc"]
    dex = dex or KyberSwap(cfg.get("dex", {}))

    tokens = tokens_fn()
    wanted = set(cfg.get("tickers") or [])
    if wanted:
        tokens = [t for t in tokens if t.ticker in wanted]
    exclude = set(cfg.get("exclude_tickers") or [])
    tokens = [t for t in tokens if t.ticker not in exclude]
    log.info("%d token Coinbase da analizzare", len(tokens))
    ysyms: Dict[str, str] = {t.ticker: yahoo_symbol(t.ticker, cfg.get("yahoo_symbols") or {})
                             for t in tokens}
    ws_refs = wallstreet_fn(ysyms, cfg.get("reference_source", "yahoo"))
    feeds = {k: v for k, v in (cfg.get("chainlink_feeds") or {}).items() if k in ysyms}
    cl_refs = chainlink_fn(feeds, cfg["base"]["rpc_url"])

    opps: List[Opportunity] = []
    no_ref: List[str] = []
    no_route: List[str] = []
    for t in tokens:
        ws, cl = ws_refs.get(t.ticker), cl_refs.get(t.ticker)
        fair_info = pick_fair(t, ws, cl, now, max_age)
        if not fair_info:
            no_ref.append(t.symbol)
            continue
        fair, src, age = fair_info
        qfn = make_quote_fn(dex, t, usdc)
        probe = float(th.get("probe_usd", 100))
        try:
            qb = qfn(sizing.BUY_TOKEN, probe)
            buy_px = probe / qb.amount_out
            qs = qfn(sizing.SELL_TOKEN, probe / fair)
            sell_px = qs.amount_out / (probe / fair)
        except NoRouteError:
            no_route.append(t.symbol)
            continue
        except Exception as e:  # noqa: BLE001
            log.warning("%s: quota on-chain non disponibile (%s)", t.symbol, e)
            continue

        mid = (buy_px + sell_px) / 2
        gap = (mid - fair) / fair * 1e4
        cm = cost_model(cfg, session, t.multiplier)
        direction = sizing.direction_for(buy_px, sell_px, fair)
        # margine per token già alla size minima: se non copre i costi della
        # copertura nessuna size è profittevole (il margine cala con la size)
        if direction and sizing.unit_edge(direction, buy_px, sell_px, fair, cm) <= 0:
            direction = None
        best = fixed = None
        if direction:
            best = sizing.optimal(direction, fair, qfn, cm,
                                  th.get("ladder_usd", [250, 500, 1000, 2500, 5000, 10000, 25000]),
                                  th.get("max_notional_usd", 25000))
            fixed = sizing.evaluate(direction, th.get("trade_size_usd", 1000), fair, qfn, cm)

        notes = []
        if session not in tradable:
            notes.append(f"Borsa {session}: gap indicativo, copertura non possibile")
        if age > max_age:
            notes.append(f"prezzo di riferimento vecchio di {age:.0f} min")
        if t.paused_features:
            notes.append(f"funzioni in pausa sul contratto: {t.paused_features}")
        if ws and cl and _age_min(now, cl.as_of) <= max_age:
            diff = (ws.price * t.multiplier - cl.price) / cl.price * 1e4
            if abs(diff) > th.get("chainlink_divergence_bps", 100):
                notes.append(f"Yahoo e Chainlink divergono di {diff:+.0f} bps")

        actionable = bool(
            best and session in tradable and age <= max_age and not t.paused_features
            and best.net_profit_usd >= th.get("min_net_profit_usd", 20)
            and best.net_bps >= th.get("min_net_bps", 30)
        )
        opps.append(Opportunity(
            ticker=t.ticker, symbol=t.symbol, address=t.address, session=session,
            fair_price=fair, ref_source=src, ref_age_min=age,
            dex_buy_price=buy_px, dex_sell_price=sell_px, gap_bps=gap,
            chainlink_price=cl.price if cl else None,
            best=best, fixed=fixed, actionable=actionable, notes=notes,
        ))
    if no_ref:
        log.warning("Senza prezzo di riferimento (%d): %s", len(no_ref), ", ".join(no_ref))
    if no_route:
        log.info("Senza liquidità instradabile on-chain (%d): %s", len(no_route), ", ".join(no_route))
    return opps


def append_csv(opps: List[Opportunity], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    new = not path.exists()
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    with path.open("a", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=CSV_FIELDS)
        if new:
            w.writeheader()
        for o in opps:
            b, f = o.best, o.fixed
            w.writerow({
                "time_utc": now, "ticker": o.ticker, "symbol": o.symbol, "session": o.session,
                "fair_price": round(o.fair_price, 4), "ref_source": o.ref_source,
                "ref_age_min": round(o.ref_age_min, 1),
                "chainlink_price": o.chainlink_price or "",
                "dex_buy": round(o.dex_buy_price, 4), "dex_sell": round(o.dex_sell_price, 4),
                "gap_bps": round(o.gap_bps, 1), "direction": b.direction if b else "",
                "opt_size_usd": round(b.notional_usd, 2) if b else "",
                "opt_tokens": round(b.tokens, 6) if b else "",
                "opt_net_profit_usd": round(b.net_profit_usd, 2) if b else "",
                "opt_net_bps": round(b.net_bps, 1) if b else "",
                "fixed_size_usd": round(f.notional_usd, 2) if f else "",
                "fixed_net_profit_usd": round(f.net_profit_usd, 2) if f else "",
                "actionable": o.actionable, "notes": "; ".join(o.notes),
            })


def print_table(opps: List[Opportunity]) -> None:
    hdr = (f"{'TOKEN':<8}{'BORSA/TK':>10}{'DEX BUY':>10}{'DEX SELL':>10}{'GAP bps':>9}"
           f"{'SIZE $':>10}{'NETTO $':>10}{'NETTO bps':>10}  AZIONE")
    print(hdr)
    print("-" * len(hdr))
    for o in sorted(opps, key=lambda x: -(x.best.net_profit_usd if x.best else -1e9)):
        b = o.best
        print(f"{o.symbol:<8}{o.fair_price:>10.2f}{o.dex_buy_price:>10.2f}"
              f"{o.dex_sell_price:>10.2f}{o.gap_bps:>+9.0f}"
              f"{(b.notional_usd if b else 0):>10,.0f}{(b.net_profit_usd if b else 0):>10,.2f}"
              f"{(b.net_bps if b else 0):>10.0f}  "
              f"{'✅ ' if o.actionable else ''}{b.direction if b else '-'}")

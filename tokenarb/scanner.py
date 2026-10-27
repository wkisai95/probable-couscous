"""Ciclo di scansione: prezzi -> gap -> size/profitto -> filtri."""
from __future__ import annotations

import csv
import logging
from datetime import datetime, timezone
from pathlib import Path
from typing import List

from . import sizing
from .market_hours import DEFAULT_HOLIDAYS, session_at
from .models import Opportunity
from .providers import build_venues, fetch_reference

log = logging.getLogger(__name__)

CSV_FIELDS = [
    "time_utc", "ticker", "venue", "symbol", "session", "ref_price", "ref_source",
    "ref_age_min", "token_bid", "token_ask", "gap_bps", "direction", "opt_qty",
    "opt_notional_usd", "opt_net_profit_usd", "opt_net_bps", "fixed_notional_usd",
    "fixed_net_profit_usd", "actionable", "notes",
]


def cost_model(cfg: dict, venue_cfg: dict, session: str, shares_per_token: float) -> sizing.CostModel:
    s = cfg.get("stock_costs", {})
    hs = s.get("half_spread_bps_regular", 2.0) if session == "regular" \
        else s.get("half_spread_bps_extended", 15.0)
    return sizing.CostModel(
        token_fee_bps=venue_cfg.get("taker_fee_bps", 25.0),
        stock_fee_bps=s.get("fee_bps", 0.0),
        stock_fee_per_share=s.get("fee_per_share", 0.0),
        stock_half_spread_bps=hs,
        fixed_cost_usd=venue_cfg.get("fixed_cost_usd", 0.0) + s.get("fixed_cost_usd", 0.0),
        shares_per_token=shares_per_token,
    )


def scan(cfg: dict) -> List[Opportunity]:
    now = datetime.now(timezone.utc)
    holidays = set(cfg.get("holidays", [])) | DEFAULT_HOLIDAYS
    session = session_at(now, holidays)
    tickers = list(cfg["tickers"])
    multipliers = cfg.get("shares_per_token", {}) or {}
    th = cfg.get("thresholds", {})
    tradable = set(cfg.get("tradable_sessions", ["regular"]))
    max_age = th.get("max_ref_age_minutes", 20)

    refs = fetch_reference(tickers, cfg.get("reference_source", "yahoo"))
    venues = build_venues(cfg)
    opps: List[Opportunity] = []

    for v in venues:
        vcfg = cfg["venues"][v.name]
        for t in tickers:
            ref = refs.get(t)
            if not ref:
                log.warning("Nessun prezzo di riferimento per %s", t)
                continue
            try:
                q = v.fetch(t)
            except Exception as e:  # noqa: BLE001
                log.warning("%s %s: %s", v.name, t, e)
                continue
            if not q or q.book.mid is None:
                continue

            mult = float(multipliers.get(t, 1.0))
            ref_px = ref.price * mult
            age = (now - ref.as_of).total_seconds() / 60
            gap = (q.book.mid - ref_px) / ref_px * 1e4
            cm = cost_model(cfg, vcfg, session, mult)
            direction = sizing.direction_for(q.book, ref_px)
            best = fixed = None
            if direction:
                best = sizing.optimal(q.book, direction, ref_px, cm,
                                      th.get("max_notional_usd", 25000))
                fixed = sizing.fixed_size(q.book, direction, ref_px, cm,
                                          th.get("trade_size_usd", 1000))

            notes = []
            if session not in tradable:
                notes.append(f"Borsa {session}: gap indicativo, non copribile")
            if age > max_age:
                notes.append(f"prezzo Borsa vecchio di {age:.0f} min")
            actionable = bool(
                best and session in tradable and age <= max_age
                and best.net_profit_usd >= th.get("min_net_profit_usd", 20)
                and best.net_bps >= th.get("min_net_bps", 30)
            )
            opps.append(Opportunity(
                ticker=t, venue=v.name, symbol=q.symbol, session=session,
                ref_price=ref_px, ref_source=ref.source, ref_age_min=age,
                token_bid=q.book.best_bid or 0.0, token_ask=q.book.best_ask or 0.0,
                token_mid=q.book.mid, gap_bps=gap, best=best, fixed=fixed,
                actionable=actionable, notes=notes,
            ))
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
                "time_utc": now, "ticker": o.ticker, "venue": o.venue, "symbol": o.symbol,
                "session": o.session, "ref_price": round(o.ref_price, 4),
                "ref_source": o.ref_source, "ref_age_min": round(o.ref_age_min, 1),
                "token_bid": o.token_bid, "token_ask": o.token_ask,
                "gap_bps": round(o.gap_bps, 1), "direction": b.direction if b else "",
                "opt_qty": round(b.qty, 6) if b else "",
                "opt_notional_usd": round(b.notional_usd, 2) if b else "",
                "opt_net_profit_usd": round(b.net_profit_usd, 2) if b else "",
                "opt_net_bps": round(b.net_bps, 1) if b else "",
                "fixed_notional_usd": round(f.notional_usd, 2) if f else "",
                "fixed_net_profit_usd": round(f.net_profit_usd, 2) if f else "",
                "actionable": o.actionable, "notes": "; ".join(o.notes),
            })


def print_table(opps: List[Opportunity]) -> None:
    hdr = f"{'TICKER':<7}{'VENUE':<8}{'BORSA':>10}{'TOKEN MID':>11}{'GAP bps':>9}" \
          f"{'SIZE $':>10}{'NETTO $':>10}{'NETTO bps':>10}  AZIONE"
    print(hdr)
    print("-" * len(hdr))
    for o in sorted(opps, key=lambda x: -(x.best.net_profit_usd if x.best else -1e9)):
        b = o.best
        print(f"{o.ticker:<7}{o.venue:<8}{o.ref_price:>10.2f}{o.token_mid:>11.2f}"
              f"{o.gap_bps:>+9.0f}"
              f"{(b.notional_usd if b else 0):>10,.0f}"
              f"{(b.net_profit_usd if b else 0):>10,.2f}"
              f"{(b.net_bps if b else 0):>10.0f}  "
              f"{'✅ ' if o.actionable else ''}{(b.direction if b else '-')}")

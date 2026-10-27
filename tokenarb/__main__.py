"""CLI.

  python -m tokenarb scan           # una scansione (usata da GitHub Actions)
  python -m tokenarb run            # loop continuo ogni 15 minuti
  python -m tokenarb tokens         # elenca tutti i token Coinbase su Base
  python -m tokenarb test-notify    # invia un messaggio di prova
"""
from __future__ import annotations

import argparse
import logging
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import yaml

from .notifier import Notifier
from .scanner import append_csv, print_table, scan

log = logging.getLogger("tokenarb")


def load_config(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


class _Collect(logging.Handler):
    """Raccoglie i messaggi di log per il riepilogo su GitHub Actions."""

    def __init__(self):
        super().__init__(logging.INFO)
        self.records: list[logging.LogRecord] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.records.append(record)


def _gh_escape(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def github_summary(opps, records) -> None:
    """Su GitHub Actions: annotazioni (visibili nella pagina della run) e step summary."""
    rows = sorted(opps, key=lambda x: -(x.best.net_profit_usd if x.best else -1e9))
    lines = [f"{'TOKEN':<8}{'BORSA':>9}{'BUY':>9}{'SELL':>9}{'GAP':>7}{'SIZE$':>8}{'NETTO$':>9}  NOTE"]
    for o in rows:
        b = o.best
        lines.append(
            f"{o.symbol:<8}{o.fair_price:>9.2f}{o.dex_buy_price:>9.2f}{o.dex_sell_price:>9.2f}"
            f"{o.gap_bps:>+7.0f}{(b.notional_usd if b else 0):>8.0f}{(b.net_profit_usd if b else 0):>9.2f}"
            f"  {'OK ' if o.actionable else ''}{(b.direction if b else '-')} {'; '.join(o.notes)[:80]}")
    info = [r.getMessage() for r in records if r.levelno == logging.INFO]
    warns = [r.getMessage() for r in records if r.levelno >= logging.WARNING]
    head = "\n".join(m for m in info if "token" in m.lower() or "opportunità" in m
                     or m.startswith("Senza"))
    print(f"::notice title=Scansione::{_gh_escape(head + chr(10) + chr(10).join(lines))}")
    if warns:
        print(f"::warning title=Avvisi ({len(warns)})::{_gh_escape(chr(10).join(warns[:80]))}")
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(f"### Scansione tokenarb\n\n{head}\n\n```\n" + "\n".join(lines) + "\n```\n")
            if warns:
                fh.write("\n<details><summary>Avvisi</summary>\n\n```\n" + "\n".join(warns) + "\n```\n</details>\n")


def list_tokens_cmd(with_liquidity: bool) -> None:
    from .coinbase import list_tokens

    toks = sorted(list_tokens(), key=lambda x: x.symbol)
    liq = {}
    if with_liquidity:
        from .liquidity import token_pools
        liq = token_pools([t.address for t in toks])
    lines = []
    for t in toks:
        line = f"{t.symbol:<9}{t.multiplier:>7.4f}  {t.address}"
        if t.paused_features:
            line += f"  [PAUSA {t.paused_features}]"
        info = liq.get(t.address.lower())
        if info is not None:
            line += (f"  pool={len(info.pools)} liq=${info.total_usd:,.0f} "
                     f"{'/'.join(info.dexes())}")
        lines.append(line)
    print("\n".join(lines))
    print(f"\n{len(toks)} token Coinbase su Base")
    if with_liquidity and os.getenv("GITHUB_ACTIONS") == "true":
        ranked = sorted(toks, key=lambda t: -(liq[t.address.lower()].total_usd
                                              if t.address.lower() in liq else 0))
        with_pools = [t for t in ranked if liq.get(t.address.lower()) and liq[t.address.lower()].pools]
        dex_count: dict = {}
        for t in with_pools:
            for d in liq[t.address.lower()].dexes():
                dex_count[d] = dex_count.get(d, 0) + 1
        head = (f"{len(toks)} token Coinbase, {len(with_pools)} con pool DEX su Base\n"
                f"Token per DEX: " + ", ".join(f"{d}={n}" for d, n in
                                               sorted(dex_count.items(), key=lambda x: -x[1])))
        rows = []
        for t in with_pools:
            i = liq[t.address.lower()]
            top = max(i.pools, key=lambda p: p.reserve_usd)
            rows.append(f"{t.symbol:<9} liq=${i.total_usd:>12,.0f}  pool={len(i.pools)}  "
                        f"top={top.dex} {top.name} vol24h=${top.volume_24h_usd:,.0f}")
        no_pools = [t.symbol for t in toks if t not in with_pools]
        print(f"::notice title=Liquidità token::{_gh_escape(head + chr(10) + chr(10).join(rows))}")
        print(f"::notice title=Senza pool ({len(no_pools)})::{_gh_escape(', '.join(no_pools))}")


def run_once(cfg: dict, notify: bool) -> None:
    collect = _Collect()
    logging.getLogger().addHandler(collect)
    try:
        opps = scan(cfg)
        print_table(opps)
        out = cfg.get("output", {})
        append_csv(opps, Path(out.get("csv", "data/scans.csv")))
        actionable = [o for o in opps if o.actionable]
        log.info("%d coppie analizzate, %d opportunità sopra soglia", len(opps), len(actionable))
        if notify and actionable:
            n = Notifier(cfg.get("notifications", {}), Path(out.get("state", "state/alerts.json")))
            log.info("Notifiche inviate: %d", n.notify(actionable))
        if os.getenv("GITHUB_ACTIONS") == "true":
            github_summary(opps, collect.records)
    finally:
        logging.getLogger().removeHandler(collect)


def main() -> None:
    p = argparse.ArgumentParser(prog="tokenarb")
    p.add_argument("command", choices=["scan", "run", "tokens", "test-notify"])
    p.add_argument("--config", default="config.yaml")
    p.add_argument("--no-notify", action="store_true")
    p.add_argument("--liquidity", action="store_true",
                   help="con 'tokens': mostra i pool DEX di ogni token (GeckoTerminal)")
    p.add_argument("-v", "--verbose", action="store_true")
    a = p.parse_args()
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config(a.config)

    if a.command == "test-notify":
        Notifier(cfg.get("notifications", {}), Path("state/alerts.json")).send(
            "✅ tokenarb: notifiche configurate correttamente")
        return
    if a.command == "scan":
        run_once(cfg, notify=not a.no_notify)
        return
    if a.command == "tokens":
        list_tokens_cmd(with_liquidity=a.liquidity)
        return

    interval = int(cfg.get("interval_minutes", 15)) * 60
    while True:
        try:
            run_once(cfg, notify=not a.no_notify)
        except Exception:  # noqa: BLE001
            log.exception("Scansione fallita")
        # allinea al prossimo quarto d'ora (:00, :15, :30, :45)
        now = datetime.now(timezone.utc).timestamp()
        time.sleep(interval - (now % interval) + 5)


if __name__ == "__main__":
    main()

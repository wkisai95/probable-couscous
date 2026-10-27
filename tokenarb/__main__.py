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


def gh_notice(title: str, text: str, level: str = "notice", limit: int = 3800) -> None:
    """Annotazione GitHub; i testi lunghi vengono divisi in più blocchi (GitHub tronca a ~4 KB)."""
    chunks, cur = [], ""
    for line in text.split("\n"):
        if cur and len(cur) + len(line) + 1 > limit:
            chunks.append(cur)
            cur = ""
        cur = f"{cur}\n{line}" if cur else line
    chunks.append(cur)
    for i, c in enumerate(chunks):
        t = title if len(chunks) == 1 else f"{title} ({i + 1}/{len(chunks)})"
        print(f"::{level} title={t}::{_gh_escape(c)}")


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
    gh_notice("Scansione", head + "\n" + "\n".join(lines))
    if warns:
        gh_notice(f"Avvisi ({len(warns)})", "\n".join(warns[:80]), level="warning")
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
            px = f"{top.token_price_usd:,.2f}" if top.token_price_usd else "n/d"
            rows.append(f"{t.symbol:<9} px={px:>9}  liq=${i.total_usd:>12,.0f}  pool={len(i.pools)}  "
                        f"top={top.dex} {top.name} vol24h=${top.volume_24h_usd:,.0f}")
        no_pools = [t.symbol for t in toks if t not in with_pools]
        gh_notice("Liquidità token", head + "\n" + "\n".join(rows))
        gh_notice(f"Senza pool ({len(no_pools)})", ", ".join(no_pools))


def feeds_cmd() -> None:
    """Confronta i feed di prezzi on-chain: GeckoTerminal (come l'app 1.0.8) e DexScreener."""
    import time as _t

    import requests

    from .coinbase import list_tokens
    from .dexscreener import API as DS_API, fetch_pools, best_usdc_pool, parse_pairs

    toks = list_tokens()
    addrs = [t.address for t in toks]
    sym = {t.address.lower(): t.symbol for t in toks}
    lines: list[str] = [f"{len(toks)} token Coinbase su Base"]

    # GeckoTerminal: 8 richieste in sequenza, una ripetizione dopo 2 s in caso di 429
    gt_price: dict[str, tuple[float, float]] = {}
    gt_fail = gt_429 = 0
    t0 = _t.perf_counter()
    hdr = {"Accept": "application/json;version=20230302", "User-Agent": "tokenarb-scanner/1.0"}
    for i in range(0, len(addrs), 30):
        url = ("https://api.geckoterminal.com/api/v2/networks/base/tokens/multi/"
               + ",".join(addrs[i:i + 30]) + "?include=top_pools")
        try:
            r = requests.get(url, headers=hdr, timeout=30)
            if r.status_code == 429:
                gt_429 += 1
                _t.sleep(2)
                r = requests.get(url, headers=hdr, timeout=30)
            r.raise_for_status()
            d = r.json()
            for tok in d.get("data", []):
                a = tok.get("attributes", {})
                px = float(a.get("price_usd") or 0)
                res = float(a.get("total_reserve_in_usd") or 0)
                if px > 0:
                    gt_price[a.get("address", "").lower()] = (px, res)
        except Exception as e:  # noqa: BLE001
            gt_fail += 1
            log.warning("GeckoTerminal: %s", e)
    gt_s = _t.perf_counter() - t0
    gt_liquid = sum(1 for _, (p, r) in gt_price.items() if r >= 1000)
    lines.append(f"GeckoTerminal: {gt_s:.1f} s · 8 richieste in sequenza · 429={gt_429} fallite={gt_fail} · "
                 f"prezzo per {len(gt_price)} token · con liquidità ≥ $1k: {gt_liquid}")

    # GeckoTerminal come nell'app 1.0.10: 4 richieste in parallelo, ripetizione dopo 1,5 s / 3 s su 429
    from concurrent.futures import ThreadPoolExecutor
    _t.sleep(5)
    par = {"429": 0, "fail": 0, "tokens": 0}

    def gt_job(chunk):
        url = ("https://api.geckoterminal.com/api/v2/networks/base/tokens/multi/"
               + ",".join(chunk) + "?include=top_pools")
        for attempt in range(3):
            r = requests.get(url, headers=hdr, timeout=30)
            if r.status_code == 429 and attempt < 2:
                par["429"] += 1
                _t.sleep(1.5 * (attempt + 1))
                continue
            r.raise_for_status()
            return len(r.json().get("data", []))
        return 0

    t0 = _t.perf_counter()
    with ThreadPoolExecutor(max_workers=4) as ex:
        for f in [ex.submit(gt_job, addrs[i:i + 30]) for i in range(0, len(addrs), 30)]:
            try:
                par["tokens"] += f.result()
            except Exception:  # noqa: BLE001
                par["fail"] += 1
    lines.append(f"GeckoTerminal parallelo (4): {_t.perf_counter() - t0:.1f} s · 429={par['429']} "
                 f"fallite={par['fail']} · token restituiti {par['tokens']}")

    # DexScreener: richieste parallele, blocchi da 30 e da 10
    results = {}
    for chunk in (30, 10):
        by_tok, st = fetch_pools(addrs, chunk=chunk, workers=8)
        best = {a: best_usdc_pool(p) for a, p in by_tok.items()}
        best = {a: b for a, b in best.items() if b}
        aero = {a: b for a, b in best.items() if b.is_aero}
        liquid = {a: b for a, b in best.items() if b.liquidity_usd >= 1000}
        with_pairs = sum(1 for p in by_tok.values() if p)
        results[chunk] = (by_tok, best, liquid)
        lines.append(
            f"DexScreener blocchi da {chunk}: {st.seconds:.1f} s · {st.requests} richieste parallele "
            f"(fallite {st.failed}) · {st.pairs} pool · token con pool {with_pairs} · "
            f"con pool USDC {len(best)} (Aero {len(aero)}) · con liquidità ≥ $1k: {len(liquid)}")
        lines.append("  dexId: " + ", ".join(f"{k}={v}" for k, v in sorted(st.dex_ids.items(), key=lambda x: -x[1])))

    # verifica di troncamento: per i token senza pool col blocco da 30, chiedo le pool una per una
    by30 = results[30][0]
    missing = [a for a in addrs if not by30.get(a.lower())][:40]
    found_single = []
    s = requests.Session()
    t0 = _t.perf_counter()
    for a in missing:
        try:
            r = s.get(f"{DS_API}/token-pairs/v1/base/{a}", headers={"Accept": "application/json"}, timeout=20)
            if r.status_code == 200:
                pools = parse_pairs(r.json() if isinstance(r.json(), list) else [], [a])
                b = best_usdc_pool(pools)
                if b:
                    found_single.append(f"{sym[a.lower()]} {b.dex} liq=${b.liquidity_usd:,.0f}")
        except Exception:  # noqa: BLE001
            pass
    lines.append(f"Controllo singolo di {len(missing)} token senza pool nel blocco da 30 "
                 f"({_t.perf_counter() - t0:.1f} s): {len(found_single)} hanno in realtà una pool USDC"
                 + (": " + "; ".join(found_single[:15]) if found_single else ""))

    # coerenza dei prezzi tra le due fonti sui token liquidi
    liquid30 = results[30][2]
    diffs = []
    for a, b in sorted(liquid30.items(), key=lambda x: -x[1].liquidity_usd):
        g = gt_price.get(a)
        if g:
            diffs.append(f"{sym[a]:<8} DS {b.price_usd:>10.2f}  GT {g[0]:>10.2f}  "
                         f"Δ {(b.price_usd - g[0]) / g[0] * 1e4:+6.0f} bps  {b.dex:<16} liq ${b.liquidity_usd:,.0f}")
        else:
            diffs.append(f"{sym[a]:<8} DS {b.price_usd:>10.2f}  GT       n/d   {b.dex:<16} liq ${b.liquidity_usd:,.0f}")
    only_ds = [sym[a] for a in liquid30 if a not in gt_price or gt_price[a][1] < 1000]
    only_gt = [sym[a] for a, (p, r) in gt_price.items() if r >= 1000 and a not in liquid30]
    lines.append(f"Liquidi solo su DexScreener ({len(only_ds)}): {', '.join(only_ds)}")
    lines.append(f"Liquidi solo su GeckoTerminal ({len(only_gt)}): {', '.join(only_gt)}")
    print("\n".join(lines))
    print("\n".join(diffs))
    if os.getenv("GITHUB_ACTIONS") == "true":
        gh_notice("Confronto feed", "\n".join(lines))
        gh_notice("Prezzi DexScreener vs GeckoTerminal", "\n".join(diffs))


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
    p.add_argument("command", choices=["scan", "run", "tokens", "feeds", "test-notify"])
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
    if a.command == "feeds":
        feeds_cmd()
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

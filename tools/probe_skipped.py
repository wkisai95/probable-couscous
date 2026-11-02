"""Controllo dei token che l'app segna come "senza pool con liquidità".

Per ogni token Coinbase (Base) e Robinhood (Robinhood Chain) rifà la copertura dell'app
(DexScreener + GeckoTerminal, pool da almeno $20) e, per quelli rimasti fuori, verifica:
  - tutte le pool su DexScreener (/token-pairs, non solo la principale);
  - se KyberSwap riesce a comprarli con $10 (liquidità instradabile anche se non indicizzata),
    e a che prezzo rispetto alla Borsa (bid/ask Robinhood).
Uso: python tools/probe_skipped.py   (su GitHub Actions pubblica il rapporto come annotazioni)
"""
from __future__ import annotations

import os
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor

import requests

UA = {"User-Agent": "Mozilla/5.0 (Android) TokenArb/1.0", "Accept": "application/json"}
CHAINS = {
    "base": {"ds": "base", "gt": "base", "kyber": "base",
             "stable": "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "dec": 6},
    "robinhood": {"ds": "robinhood", "gt": "robinhood", "kyber": "robinhood",
                  "stable": "0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168", "dec": 6},
}
MIN_POOL_USD = 20.0
out: list[str] = []


def log(title, text):
    print(f"== {title}\n{text}\n")
    out.append((title, text))


def get(url, **kw):
    return requests.get(url, headers={**UA, **kw.pop("headers", {})}, timeout=30, **kw)


# ---- elenchi token
tokens = []   # (addr_lower, addr, symbol, ticker, decimals, multiplier, chain)
page = None
for _ in range(50):
    url = "https://api.coinbase.com/v1/tokenized-stocks?page_size=500" + (f"&page_token={page}" if page else "")
    d = get(url).json()
    for t in d.get("tokens", []):
        sym = t.get("symbol", "")
        tokens.append((t["contract_address"].lower(), t["contract_address"], sym,
                       sym[:-1] if sym.endswith("c") else sym, int(t.get("decimals") or 18),
                       float(t.get("multiplier") or 1), "base"))
    page = d.get("next_page_token")
    if not page:
        break
for a in get("https://api.robinhood.com/rhj/assets").json().get("assets", []):
    if a.get("status") != "ASSET_STATUS_ACTIVE":
        continue
    dep = next((x for x in a.get("deployments", []) if x.get("chainId") == 4663), None)
    if dep:
        tokens.append((dep["contractAddress"].lower(), dep["contractAddress"], a["tokenSymbol"], a["tokenSymbol"],
                       18, float(a.get("currentMultiplier") or 1), "robinhood"))
fair = {}
for q in get("https://api.robinhood.com/rhj/prices").json().get("quotes", []):
    try:
        fair[q["tokenSymbol"]] = (float(q["bid"]) + float(q["ask"])) / 2
    except (KeyError, ValueError):
        pass
by_chain = Counter(t[6] for t in tokens)


def pool_price(p, addr):
    """Prezzo del token `addr` in una pair DexScreener e liquidità."""
    b = p["baseToken"]["address"].lower()
    try:
        usd = float(p.get("priceUsd") or 0)
        nat = float(p.get("priceNative") or 0)
    except ValueError:
        return None
    px = usd if b == addr else (usd / nat if nat > 0 else 0)
    return px, float((p.get("liquidity") or {}).get("usd") or 0)


# ---- copertura come nell'app: DexScreener (pool principale) + GeckoTerminal (pool principali)
covered = set()
for chain, cfg in CHAINS.items():
    addrs = [t[1] for t in tokens if t[6] == chain]
    for i in range(0, len(addrs), 30):
        r = get(f"https://api.dexscreener.com/tokens/v1/{cfg['ds']}/" + ",".join(addrs[i:i + 30]))
        if r.status_code != 200:
            continue
        for p in r.json():
            for side in ("baseToken", "quoteToken"):
                a = p[side]["address"].lower()
                pp = pool_price(p, a)
                if pp and pp[0] > 0 and pp[1] >= MIN_POOL_USD:
                    covered.add(a)
ds_cov = len(covered)
gt_fail = 0
for chain, cfg in CHAINS.items():
    addrs = [t[1] for t in tokens if t[6] == chain]
    for i in range(0, len(addrs), 30):
        time.sleep(2.2)
        r = None
        for attempt in range(4):
            r = get(f"https://api.geckoterminal.com/api/v2/networks/{cfg['gt']}/tokens/multi/" + ",".join(addrs[i:i + 30])
                    + "?include=top_pools", headers={"Accept": "application/json;version=20230302"})
            if r.status_code != 429:
                break
            time.sleep(2 * 2 ** attempt)
        if r is None or r.status_code != 200:
            gt_fail += 1
            continue
        d = r.json()
        pools = {p["id"]: p for p in d.get("included", []) if p.get("type") == "pool"}
        for t in d.get("data", []):
            a = t["attributes"]["address"].lower()
            for ref in (t.get("relationships", {}).get("top_pools", {}).get("data") or []):
                p = pools.get(ref["id"])
                if p and float(p["attributes"].get("reserve_in_usd") or 0) >= MIN_POOL_USD:
                    covered.add(a)
skipped = [t for t in tokens if t[0] not in covered]
log("Copertura come nell'app",
    f"token: {dict(by_chain)} · coperti {len(covered)} (DexScreener {ds_cov}, + GeckoTerminal) · "
    f"saltati {len(skipped)} {dict(Counter(t[6] for t in skipped))} · GeckoTerminal richieste fallite {gt_fail}")

# ---- 1. tutte le pool DexScreener dei token saltati
lock = threading.Lock()
nxt = [0.0]


def pace(interval):
    with lock:
        now = time.time()
        start = max(now, nxt[0])
        nxt[0] = start + interval
    if start > now:
        time.sleep(start - now)


def ds_all(t):
    pace(0.25)
    try:
        r = get(f"https://api.dexscreener.com/token-pairs/v1/{CHAINS[t[6]]['ds']}/{t[1]}")
        pairs = r.json() if r.status_code == 200 else []
    except Exception:  # noqa: BLE001
        pairs = []
    res = []
    for p in pairs if isinstance(pairs, list) else []:
        pp = pool_price(p, t[0])
        if pp:
            res.append((pp[1], pp[0], p.get("dexId"), p["quoteToken"].get("symbol") if p["baseToken"]["address"].lower() == t[0] else p["baseToken"].get("symbol")))
    return t, res


with ThreadPoolExecutor(4) as ex:
    ds_res = list(ex.map(ds_all, skipped))
with_pools = [(t, r) for t, r in ds_res if r]
liq_b = Counter()
for t, r in with_pools:
    m = max(x[0] for x in r)
    liq_b["<$1" if m < 1 else "<$20" if m < 20 else "<$100" if m < 100 else ">=$100"] += 1
log("Tutte le pool DexScreener dei saltati",
    f"{len(with_pools)} su {len(skipped)} hanno almeno una pool · liquidità massima: {dict(liq_b)}\n" +
    "\n".join(f"{t[2]:<8} {t[6]:<9} " + "; ".join(f"{d} {q} liq ${l:,.0f} px {px:.4g}" for l, px, d, q in sorted(r, reverse=True)[:3])
              for t, r in sorted(with_pools, key=lambda x: -max(y[0] for y in x[1]))[:30]))

# ---- 2. KyberSwap: acquisto da $10
def kyber(t):
    cfg = CHAINS[t[6]]
    pace(0.25)
    try:
        r = get(f"https://aggregator-api.kyberswap.com/{cfg['kyber']}/api/v1/routes",
                params={"tokenIn": cfg["stable"], "tokenOut": t[1], "amountIn": str(10 * 10 ** cfg["dec"])},
                headers={"X-Client-Id": "tokenarb-probe"})
        d = r.json()
        if r.status_code == 200 and d.get("code") == 0:
            outq = int(d["data"]["routeSummary"]["amountOut"]) / 10 ** t[4]
            gas = float(d["data"]["routeSummary"].get("gasUsd") or 0)
            return t, "ok", (10 / outq if outq > 0 else None), gas
        return t, f"code {d.get('code')}", None, 0
    except Exception as e:  # noqa: BLE001
        return t, f"errore {type(e).__name__}", None, 0


with ThreadPoolExecutor(4) as ex:
    ky = list(ex.map(kyber, skipped))
codes = Counter(k[1] for k in ky)
ok = [k for k in ky if k[1] == "ok" and k[2]]
rows = []
for t, _, px, gas in ok:
    f = fair.get(t[3])
    gap = ((px - f * t[5]) / (f * t[5]) * 1e4) if f else None
    rows.append((gap if gap is not None else 1e9, t, px, f, gas))
rows.sort()
log("KyberSwap: acquisto da $10 dei saltati",
    f"esiti {dict(codes)} · instradabili {len(ok)} su {len(skipped)}\n" +
    "\n".join(f"{t[2]:<8} {t[6]:<9} prezzo eseguibile {px:.4g} · Borsa {('%.4g' % (f * t[5])) if f else 'n/d'} · "
              f"gap {('%+.0f bps' % g) if g < 1e8 else 'n/d'} · gas ${gas:.3f}" for g, t, px, f, gas in rows[:40]))

if os.getenv("GITHUB_ACTIONS") == "true":
    for title, text in out:
        msg = text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        for i in range(0, max(len(msg), 1), 3800):
            print(f"::notice title={title[:80]}::{msg[i:i + 3800]}")

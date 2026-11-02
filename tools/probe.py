"""Verifica delle fonti dati per gli stock token Robinhood (Robinhood Chain, ID 4663).

Uso: python tools/probe.py   (su GitHub Actions pubblica i risultati come annotazioni)
"""
from __future__ import annotations

import json
import os
import time
from collections import Counter

import requests

UA = {"User-Agent": "Mozilla/5.0 (Android) TokenArb/1.0", "Accept": "application/json"}
RPC = "https://rpc.mainnet.chain.robinhood.com"
USDG = "0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168"
WETH = "0x0Bd7D308f8E1639FAb988df18A8011f41EAcAD73"
out: list[str] = []


def log(title: str, text: str) -> None:
    print(f"== {title}\n{text}\n")
    out.append(f"## {title}\n{text}")


def get(url, **kw):
    t0 = time.perf_counter()
    r = requests.get(url, headers={**UA, **kw.pop("headers", {})}, timeout=30, **kw)
    return r, time.perf_counter() - t0


def rpc(method, params):
    r = requests.post(RPC, json={"jsonrpc": "2.0", "id": 1, "method": method, "params": params}, timeout=20)
    return r.json()


# 1. elenco token Robinhood
assets = []
try:
    r, dt = get("https://api.robinhood.com/rhj/assets")
    d = r.json()
    assets = d.get("assets", [])
    chains = Counter(dep.get("chainId") for a in assets for dep in a.get("deployments", []))
    status = Counter(a.get("status") for a in assets)
    mult = [a for a in assets if a.get("currentMultiplier") not in ("1", "1.0", "1.000000000000000000")]
    log("Robinhood /rhj/assets",
        f"HTTP {r.status_code} in {dt:.2f}s · chiavi {list(d.keys())} · asset {len(assets)} · stato {dict(status)} · "
        f"chainId {dict(chains)} · multiplier≠1: {len(mult)}\n"
        f"esempio: {json.dumps(assets[0])[:600] if assets else '-'}\n"
        f"simboli: {', '.join(a.get('tokenSymbol', '?') for a in assets[:80])}")
except Exception as e:  # noqa: BLE001
    log("Robinhood /rhj/assets", f"ERRORE {e}")

# 2. prezzi Robinhood
for path in ("prices/AAPL", "prices", "prices?symbols=AAPL,TSLA"):
    try:
        r, dt = get(f"https://api.robinhood.com/rhj/{path}")
        body = r.text
        n = len(r.json().get("quotes", [])) if r.headers.get("content-type", "").startswith("application/json") else "?"
        log(f"Robinhood /rhj/{path}", f"HTTP {r.status_code} in {dt:.2f}s · quotes={n}\n{body[:500]}")
    except Exception as e:  # noqa: BLE001
        log(f"Robinhood /rhj/{path}", f"ERRORE {e}")

# 3. RPC pubblico e decimali USDG
try:
    cid = rpc("eth_chainId", [])
    dec = rpc("eth_call", [{"to": USDG, "data": "0x313ce567"}, "latest"])
    log("RPC Robinhood Chain", f"chainId {cid.get('result')} · USDG decimals {int(dec.get('result', '0x0'), 16)}")
except Exception as e:  # noqa: BLE001
    log("RPC Robinhood Chain", f"ERRORE {e}")

addrs = [dep["contractAddress"] for a in assets for dep in a.get("deployments", [])
         if dep.get("chainId") == 4663 and a.get("status") == "ASSET_STATUS_ACTIVE"]
sym = {dep["contractAddress"].lower(): a.get("tokenSymbol") for a in assets for dep in a.get("deployments", [])}

# 4. DexScreener su Robinhood Chain
try:
    pairs = []
    t0 = time.perf_counter()
    for i in range(0, len(addrs), 30):
        r, _ = get("https://api.dexscreener.com/tokens/v1/robinhood/" + ",".join(addrs[i:i + 30]))
        if r.status_code == 200:
            pairs += r.json()
    dt = time.perf_counter() - t0
    dex = Counter(p.get("dexId") for p in pairs)
    chains = Counter(p.get("chainId") for p in pairs)
    quotes = Counter()
    toks = set()
    rows = []
    for p in pairs:
        b = p["baseToken"]["address"].lower()
        q = p["quoteToken"]["address"].lower()
        if b in sym:
            toks.add(b)
            quotes[p["quoteToken"].get("symbol", "?") + " " + q[:10]] += 1
            rows.append((p.get("liquidity", {}).get("usd", 0) or 0, sym[b], p.get("dexId"), p["quoteToken"].get("symbol"),
                         p.get("priceUsd"), " ".join(p.get("labels") or [])))
        elif q in sym:
            toks.add(q)
            quotes[p["baseToken"].get("symbol", "?") + " " + b[:10] + " (base)"] += 1
    rows.sort(reverse=True)
    liq = [x[0] for x in rows]
    buckets = Counter("<100" if v < 100 else "<1k" if v < 1e3 else "<10k" if v < 1e4 else "<100k" if v < 1e5 else ">=100k" for v in liq)
    log("DexScreener robinhood",
        f"{len(addrs)} token attivi · {len(pairs)} pool in {dt:.2f}s · token con pool {len(toks)} · chainId {dict(chains)} · "
        f"dexId {dict(dex)}\nquote asset {dict(quotes)}\nliquidità {dict(buckets)}\n"
        + "\n".join(f"{s:<7} {d:<12} {q:<6} px {px:<10} liq ${l:,.0f} {lab}" for l, s, d, q, px, lab in rows[:25])
        + "\n…\n" + "\n".join(f"{s:<7} {d:<12} {q:<6} px {px:<10} liq ${l:,.0f} {lab}" for l, s, d, q, px, lab in rows[-15:]))
except Exception as e:  # noqa: BLE001
    log("DexScreener robinhood", f"ERRORE {e}")

# 5. GeckoTerminal: id della rete e copertura
try:
    net_id = None
    for page in range(1, 12):
        r, _ = get(f"https://api.geckoterminal.com/api/v2/networks?page={page}",
                   headers={"Accept": "application/json;version=20230302"})
        if r.status_code != 200:
            break
        data = r.json().get("data", [])
        for n in data:
            name = (n.get("attributes", {}).get("name") or "").lower()
            if "robinhood" in name or "robinhood" in n.get("id", ""):
                net_id = n["id"]
        if net_id or not data:
            break
        time.sleep(1.5)
    found = 0
    if net_id and addrs:
        time.sleep(1.5)
        r, dt = get(f"https://api.geckoterminal.com/api/v2/networks/{net_id}/tokens/multi/" + ",".join(addrs[:30])
                    + "?include=top_pools", headers={"Accept": "application/json;version=20230302"})
        if r.status_code == 200:
            found = sum(1 for t in r.json().get("data", []) if float(t["attributes"].get("total_reserve_in_usd") or 0) > 0)
        log("GeckoTerminal robinhood", f"rete '{net_id}' · HTTP {r.status_code} in {dt:.2f}s · token con riserve (primi 30): {found}")
    else:
        log("GeckoTerminal robinhood", f"rete non trovata ({net_id})")
except Exception as e:  # noqa: BLE001
    log("GeckoTerminal robinhood", f"ERRORE {e}")

# 6. KyberSwap: identificativo della chain
tsla = next((a for a in addrs if sym.get(a.lower()) == "TSLA"), addrs[0] if addrs else None)
for slug in ("robinhood", "robinhood-chain", "rhc", "4663"):
    try:
        r, dt = get(f"https://aggregator-api.kyberswap.com/{slug}/api/v1/routes",
                    params={"tokenIn": USDG, "tokenOut": tsla, "amountIn": str(100 * 10**6)},
                    headers={"X-Client-Id": "tokenarb-probe"})
        log(f"KyberSwap '{slug}'", f"HTTP {r.status_code} in {dt:.2f}s · {r.text[:300]}")
        if r.status_code == 200:
            break
    except Exception as e:  # noqa: BLE001
        log(f"KyberSwap '{slug}'", f"ERRORE {e}")

if os.getenv("GITHUB_ACTIONS") == "true":
    for block in out:
        title, _, body = block.partition("\n")
        msg = body.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title={title[3:][:80]}::{msg[:3800]}")

"""Quotazioni eseguibili on-chain su Base tramite l'aggregatore KyberSwap.

L'aggregatore instrada su tutti i pool di Base (Aerodrome, Uniswap, ...), quindi
il prezzo restituito include fee dei pool e slippage reale per la size richiesta.
Docs: https://docs.kyberswap.com/kyberswap-solutions/kyberswap-aggregator/aggregator-api-specification/evm-swaps
"""
from __future__ import annotations

import logging
import time
from decimal import Decimal

import requests

from .models import DexQuote

log = logging.getLogger(__name__)


class KyberSwap:
    def __init__(self, cfg: dict):
        self.url = cfg.get("url", "https://aggregator-api.kyberswap.com/base/api/v1/routes")
        self.client_id = cfg.get("client_id", "tokenarb-scanner")
        self.delay = float(cfg.get("delay_seconds", 0.35))
        self.s = requests.Session()
        self.s.headers.update({"X-Client-Id": self.client_id})
        self._last = 0.0

    def quote(self, token_in: str, dec_in: int, token_out: str, dec_out: int,
              amount_in: float) -> DexQuote:
        wait = self.delay - (time.time() - self._last)
        if wait > 0:
            time.sleep(wait)
        raw_in = int(Decimal(str(amount_in)) * (Decimal(10) ** dec_in))
        r = self.s.get(self.url, params={"tokenIn": token_in, "tokenOut": token_out,
                                         "amountIn": str(raw_in)}, timeout=20)
        self._last = time.time()
        r.raise_for_status()
        body = r.json()
        if body.get("code") not in (0, None) or "data" not in body:
            raise RuntimeError(f"KyberSwap: {body.get('message')}")
        rs = body["data"]["routeSummary"]
        out = float(Decimal(rs["amountOut"]) / (Decimal(10) ** dec_out))
        gas = float(rs.get("gasUsd") or 0) + float(rs.get("l1FeeUsd") or 0)
        return DexQuote(amount_in=amount_in, amount_out=out, gas_usd=gas)

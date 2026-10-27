"""Notifiche: Telegram, Discord, ntfy.sh. Credenziali da variabili d'ambiente."""
from __future__ import annotations

import json
import logging
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Dict, List

import requests

from .models import Opportunity

log = logging.getLogger(__name__)

DIR_LABEL = {
    "buy_token_sell_stock": "COMPRA token / VENDI azione",
    "sell_token_buy_stock": "VENDI token / COMPRA azione",
}


def format_opportunity(o: Opportunity) -> str:
    b, f = o.best, o.fixed
    lines = [
        f"⚡ {o.symbol} su Base (Coinbase)",
        f"Gap: {o.gap_bps:+.0f} bps | on-chain buy {o.dex_buy_price:.2f} / sell "
        f"{o.dex_sell_price:.2f} vs Borsa {o.fair_price:.2f} per token "
        f"({o.ref_source}, sessione {o.session})",
    ]
    if b:
        lines += [
            f"➡️ {DIR_LABEL[b.direction]}",
            f"Size ottimale: ${b.notional_usd:,.0f} = {b.tokens:.4f} {o.symbol} "
            f"(prezzo medio on-chain {b.exec_price:.2f})",
            f"Profitto netto: ${b.net_profit_usd:,.2f} ({b.net_bps:.0f} bps) "
            f"| lordo ${b.gross_profit_usd:,.2f} - costi ${b.costs_usd:,.2f}",
        ]
        if b.ladder:
            lines.append("Scala size→netto: " + ", ".join(
                f"${s:,.0f}→${p:,.0f}" for s, p in b.ladder))
    if f:
        lines.append(f"Con size fissa ${f.notional_usd:,.0f}: netto ${f.net_profit_usd:,.2f} "
                     f"({f.net_bps:.0f} bps)")
    if o.notes:
        lines.append("Note: " + "; ".join(o.notes))
    lines.append(f"https://basescan.org/token/{o.address}")
    return "\n".join(lines)


class Notifier:
    def __init__(self, cfg: dict, state_path: Path):
        self.cfg = cfg
        self.cooldown = timedelta(minutes=cfg.get("cooldown_minutes", 60))
        self.rearm_bps = cfg.get("rearm_if_gap_widens_bps", 25)
        self.state_path = state_path
        self.state: Dict[str, dict] = {}
        if state_path.exists():
            try:
                self.state = json.loads(state_path.read_text())
            except json.JSONDecodeError:
                self.state = {}

    def _should_send(self, o: Opportunity, now: datetime) -> bool:
        key = f"{o.address}|{o.best.direction if o.best else ''}"
        prev = self.state.get(key)
        if prev:
            last = datetime.fromisoformat(prev["at"])
            widened = abs(o.gap_bps) - abs(prev["gap_bps"]) >= self.rearm_bps
            if now - last < self.cooldown and not widened:
                return False
        self.state[key] = {"at": now.isoformat(), "gap_bps": o.gap_bps}
        return True

    def notify(self, opps: List[Opportunity]) -> int:
        now = datetime.now(timezone.utc)
        to_send = [o for o in opps if self._should_send(o, now)]
        for o in to_send:
            self.send(format_opportunity(o))
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        self.state_path.write_text(json.dumps(self.state, indent=2))
        return len(to_send)

    def send(self, text: str) -> None:
        sent = False
        tg_token, tg_chat = os.getenv("TELEGRAM_BOT_TOKEN"), os.getenv("TELEGRAM_CHAT_ID")
        if tg_token and tg_chat:
            self._post(f"https://api.telegram.org/bot{tg_token}/sendMessage",
                       json={"chat_id": tg_chat, "text": text,
                             "disable_web_page_preview": True}, name="Telegram")
            sent = True
        if url := os.getenv("DISCORD_WEBHOOK_URL"):
            self._post(url, json={"content": text[:1900]}, name="Discord")
            sent = True
        if topic := os.getenv("NTFY_TOPIC"):
            server = os.getenv("NTFY_SERVER", "https://ntfy.sh")
            self._post(f"{server}/{topic}", data=text.encode(),
                       headers={"Title": "Arbitraggio stock tokenizzati"}, name="ntfy")
            sent = True
        if not sent:
            log.info("Nessun canale configurato, notifica solo a console:\n%s", text)

    @staticmethod
    def _post(url: str, name: str, **kw) -> None:
        try:
            r = requests.post(url, timeout=15, **kw)
            r.raise_for_status()
        except Exception as e:  # noqa: BLE001
            log.error("Invio %s fallito: %s", name, e)

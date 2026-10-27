"""Sessioni di mercato USA (NYSE/Nasdaq), ora di New York.

Festività gestite con una lista statica (aggiornabile in config.yaml).
"""
from __future__ import annotations

from datetime import date, datetime, time
from typing import Iterable
from zoneinfo import ZoneInfo

NY = ZoneInfo("America/New_York")

PRE_OPEN = time(4, 0)
REG_OPEN = time(9, 30)
REG_CLOSE = time(16, 0)
POST_CLOSE = time(20, 0)

# Festività NYSE 2026-2027 (mercato chiuso tutto il giorno)
DEFAULT_HOLIDAYS = {
    "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25",
    "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
    "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31",
    "2027-06-18", "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24",
}


def session_at(now: datetime, holidays: Iterable[str] = DEFAULT_HOLIDAYS) -> str:
    """Ritorna 'regular', 'pre', 'post' o 'closed'."""
    ny = now.astimezone(NY)
    if ny.weekday() >= 5 or ny.date().isoformat() in set(holidays):
        return "closed"
    t = ny.time()
    if REG_OPEN <= t < REG_CLOSE:
        return "regular"
    if PRE_OPEN <= t < REG_OPEN:
        return "pre"
    if REG_CLOSE <= t < POST_CLOSE:
        return "post"
    return "closed"


def is_trading_day(d: date, holidays: Iterable[str] = DEFAULT_HOLIDAYS) -> bool:
    return d.weekday() < 5 and d.isoformat() not in set(holidays)

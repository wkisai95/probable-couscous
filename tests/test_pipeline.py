"""Pipeline completa con dati finti (nessuna rete)."""
from datetime import datetime, timezone

from tokenarb import scanner
from tokenarb.models import OrderBook, RefQuote, TokenQuote
from tokenarb.notifier import Notifier, format_opportunity


class FakeVenue:
    name = "kraken"

    def fetch(self, t):
        book = OrderBook(bids=[(196.0, 50)], asks=[(196.5, 20), (198.0, 30), (201, 100)])
        return TokenQuote("kraken", t, f"{t}xUSD", book, datetime.now(timezone.utc))


def test_scan_end_to_end(monkeypatch, tmp_path):
    now = datetime(2026, 10, 27, 15, 0, tzinfo=timezone.utc)  # 11:00 NY, mercato aperto

    class FrozenDT(datetime):
        @classmethod
        def now(cls, tz=None):
            return now

    monkeypatch.setattr(scanner, "datetime", FrozenDT)
    monkeypatch.setattr(scanner, "fetch_reference",
                        lambda t, s: {"AAPL": RefQuote("AAPL", 200.0, now, "test")})
    monkeypatch.setattr(scanner, "build_venues", lambda cfg: [FakeVenue()])
    cfg = {
        "tickers": ["AAPL"],
        "venues": {"kraken": {"taker_fee_bps": 26}},
        "stock_costs": {"fee_per_share": 0.005, "half_spread_bps_regular": 2},
        "thresholds": {"min_net_profit_usd": 20, "min_net_bps": 30,
                       "max_notional_usd": 25000, "trade_size_usd": 1000},
    }
    opps = scanner.scan(cfg)
    assert len(opps) == 1
    o = opps[0]
    assert o.best.direction == "buy_token_sell_stock"
    assert o.best.qty == 50            # si ferma prima del livello a 201
    assert o.actionable
    scanner.append_csv(opps, tmp_path / "s.csv")
    assert (tmp_path / "s.csv").read_text().count("\n") == 2

    msg = format_opportunity(o)
    assert "COMPRA token" in msg
    n = Notifier({"cooldown_minutes": 60}, tmp_path / "st.json")
    sent = []
    monkeypatch.setattr(n, "send", sent.append)
    assert n.notify(opps) == 1
    assert n.notify(opps) == 0         # cooldown attivo

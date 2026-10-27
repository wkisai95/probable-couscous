"""Test offline con un AMM simulato (x*y=k) al posto dell'aggregatore."""
from datetime import datetime, timedelta, timezone

import pytest

from tokenarb import scanner, sizing
from tokenarb.coinbase import parse_token, ticker_from_symbol
from tokenarb.market_hours import session_at
from tokenarb.models import CbToken, DexQuote, RefQuote
from tokenarb.notifier import Notifier, format_opportunity

USDC = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
NOW = datetime(2026, 10, 27, 15, 0, tzinfo=timezone.utc)  # 11:00 NY, Borsa aperta


class FakeAMM:
    """Pool token/USDC a prodotto costante con fee 0,3%."""

    def __init__(self, token_reserve, usdc_reserve, fee=0.003, gas=0.02):
        self.t, self.u, self.fee, self.gas = token_reserve, usdc_reserve, fee, gas
        self.calls = 0

    def quote(self, token_in, dec_in, token_out, dec_out, amount_in):
        self.calls += 1
        a = amount_in * (1 - self.fee)
        if token_in == USDC:
            out = self.t * a / (self.u + a)
        else:
            out = self.u * a / (self.t + a)
        return DexQuote(amount_in, out, self.gas)


def amm_quote_fn(amm):
    def q(direction, amount):
        if direction == sizing.BUY_TOKEN:
            return amm.quote(USDC, 6, "T", 18, amount)
        return amm.quote("T", 18, USDC, 6, amount)
    return q


NO_COST = sizing.CostModel(stock_half_spread_bps=0)


def test_ticker_and_parse():
    assert ticker_from_symbol("AAPLc") == "AAPL"
    t = parse_token({"contract_address": "0xb2", "symbol": "NVDAc", "decimals": 18,
                     "multiplier": 1.02, "nav_price": 190.5,
                     "nav_price_updated_at": "2026-10-27T14:00:00Z"})
    assert (t.ticker, t.multiplier, t.nav_price) == ("NVDA", 1.02, 190.5)


def test_yahoo_symbol():
    assert scanner.yahoo_symbol("BRK.B", {}) == "BRK-B"
    assert scanner.yahoo_symbol("SPCX", {"SPCX": "XYZ"}) == "XYZ"


def test_direction():
    assert sizing.direction_for(98, 97.5, 100) == sizing.BUY_TOKEN
    assert sizing.direction_for(102.5, 102, 100) == sizing.SELL_TOKEN
    assert sizing.direction_for(100.2, 99.8, 100) is None


def test_optimal_size_on_amm_is_interior_maximum():
    # pool a 196 $/token, Borsa a 200: profitto concavo nella size
    amm = FakeAMM(token_reserve=1000, usdc_reserve=196_000)
    best = sizing.optimal(sizing.BUY_TOKEN, 200, amm_quote_fn(amm), NO_COST,
                          [250, 500, 1000, 2500, 5000, 10000, 25000], 25000)
    assert best.net_profit_usd > 0
    # massimo teorico: il prezzo marginale arriva a 200*(1-fee) -> size ~ 1,7k$
    assert 1000 < best.notional_usd < 2600
    # oltre il massimo il profitto scende
    big = sizing.evaluate(sizing.BUY_TOKEN, 10000, 200, amm_quote_fn(amm), NO_COST)
    assert big.net_profit_usd < best.net_profit_usd
    assert best.ladder and best.ladder[0][0] == pytest.approx(250, rel=1e-6)


def test_costs_are_subtracted():
    amm = FakeAMM(1000, 196_000, gas=0.5)
    cm = sizing.CostModel(stock_fee_per_share=0.01, stock_half_spread_bps=10,
                          fixed_cost_usd=1, multiplier=1.0)
    r = sizing.evaluate(sizing.BUY_TOKEN, 1000, 200, amm_quote_fn(amm), cm)
    expected_costs = 0.5 + r.tokens * 0.01 + 1
    assert r.costs_usd == pytest.approx(expected_costs)
    assert r.net_profit_usd == pytest.approx(r.gross_profit_usd - expected_costs)


def test_multiplier_applied_to_fair_price():
    t = CbToken("0xb2", "AAPLc", "AAPL", "Apple", 18, multiplier=1.02)
    ws = RefQuote("AAPL", 200.0, NOW, "yahoo")
    fair, src, _ = scanner.pick_fair(t, ws, None, NOW, 20)
    assert fair == pytest.approx(204.0) and src == "yahoo"
    # Yahoo vecchio -> Chainlink fresco (già per token)
    old = RefQuote("AAPL", 200.0, NOW - timedelta(hours=3), "yahoo")
    cl = RefQuote("AAPL", 203.5, NOW, "chainlink", per_token=True)
    assert scanner.pick_fair(t, old, cl, NOW, 20)[:2] == (203.5, "chainlink")


def _cfg():
    return {
        "tickers": [],
        "base": {"rpc_url": "x", "usdc": {"address": USDC, "decimals": 6}},
        "stock_costs": {"fee_per_share": 0.005, "half_spread_bps_regular": 2},
        "thresholds": {"min_net_profit_usd": 5, "min_net_bps": 10,
                       "max_notional_usd": 25000, "trade_size_usd": 1000},
        "chainlink_feeds": {"AAPL": "0xfeed"},
    }


def test_scan_end_to_end(monkeypatch, tmp_path):
    class Frozen(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    monkeypatch.setattr(scanner, "datetime", Frozen)
    tok = CbToken("0xb200000000000000000000C2e324d24d7eEcd1fb", "AAPLc", "AAPL",
                  "Apple", 18, 1.0)
    amm = FakeAMM(1000, 196_000)
    opps = scanner.scan(
        _cfg(),
        tokens_fn=lambda: [tok],
        wallstreet_fn=lambda syms, src: {"AAPL": RefQuote("AAPL", 200.0, NOW, "yahoo")},
        chainlink_fn=lambda feeds, rpc: {"AAPL": RefQuote("AAPL", 199.9, NOW, "chainlink", True)},
        dex=amm,
    )
    assert len(opps) == 1
    o = opps[0]
    assert o.best.direction == sizing.BUY_TOKEN
    assert o.actionable and o.gap_bps < 0
    assert o.chainlink_price == 199.9

    scanner.append_csv(opps, tmp_path / "s.csv")
    assert (tmp_path / "s.csv").read_text().count("\n") == 2
    msg = format_opportunity(o)
    assert "COMPRA token" in msg and "basescan.org" in msg

    n = Notifier({"cooldown_minutes": 60}, tmp_path / "st.json")
    sent = []
    monkeypatch.setattr(n, "send", sent.append)
    assert n.notify(opps) == 1
    assert n.notify(opps) == 0  # cooldown


def test_closed_market_not_actionable(monkeypatch):
    sat = datetime(2026, 10, 31, 15, 0, tzinfo=timezone.utc)

    class Frozen(datetime):
        @classmethod
        def now(cls, tz=None):
            return sat

    monkeypatch.setattr(scanner, "datetime", Frozen)
    tok = CbToken("0xb2", "AAPLc", "AAPL", "Apple", 18, 1.0)
    opps = scanner.scan(
        _cfg(), tokens_fn=lambda: [tok],
        wallstreet_fn=lambda s, src: {"AAPL": RefQuote("AAPL", 200.0, sat, "yahoo")},
        chainlink_fn=lambda f, r: {}, dex=FakeAMM(1000, 196_000))
    assert not opps[0].actionable
    assert any("closed" in n for n in opps[0].notes)


@pytest.mark.parametrize("utc,expected", [
    ("2026-10-27T14:00:00", "regular"),
    ("2026-10-27T12:00:00", "pre"),
    ("2026-10-27T21:00:00", "post"),
    ("2026-10-28T02:00:00", "closed"),
    ("2026-10-31T15:00:00", "closed"),
    ("2026-11-26T15:00:00", "closed"),
])
def test_sessions(utc, expected):
    assert session_at(datetime.fromisoformat(utc).replace(tzinfo=timezone.utc)) == expected


def test_unit_edge_blocks_unprofitable_sizes():
    cm = sizing.CostModel(stock_fee_per_share=0.005, stock_half_spread_bps=2)
    # token appena sotto il prezzo: il margine non copre spread e commissioni
    assert sizing.unit_edge(sizing.BUY_TOKEN, 199.99, 199.0, 200, cm) < 0
    assert sizing.unit_edge(sizing.BUY_TOKEN, 198.0, 197.0, 200, cm) > 0
    assert sizing.unit_edge(sizing.SELL_TOKEN, 203.0, 202.0, 200, cm) > 0


def test_tokens_without_route_are_skipped(monkeypatch, caplog):
    from tokenarb.dex import NoRouteError

    class Frozen(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    class NoRoute:
        def quote(self, *a):
            raise NoRouteError("KyberSwap 4008: route not found")

    monkeypatch.setattr(scanner, "datetime", Frozen)
    tok = CbToken("0xb2", "ABNBc", "ABNB", "Airbnb", 18, 1.0)
    with caplog.at_level("INFO"):
        opps = scanner.scan(
            _cfg(), tokens_fn=lambda: [tok],
            wallstreet_fn=lambda s, src: {"ABNB": RefQuote("ABNB", 130.0, NOW, "yahoo")},
            chainlink_fn=lambda f, r: {}, dex=NoRoute())
    assert opps == []
    assert "Senza liquidità instradabile on-chain (1): ABNBc" in caplog.text


def test_only_buy_token_skips_sell_side(monkeypatch):
    class Frozen(datetime):
        @classmethod
        def now(cls, tz=None):
            return NOW

    monkeypatch.setattr(scanner, "datetime", Frozen)
    tok = CbToken("0xb2", "NFLXc", "NFLX", "Netflix", 18, 1.0)
    # pool a 210 $/token, Borsa a 200: il token costa di più -> lato vendita
    amm = FakeAMM(1000, 210_000)
    cfg = _cfg()
    opps = scanner.scan(cfg, tokens_fn=lambda: [tok],
                        wallstreet_fn=lambda s, src: {"NFLX": RefQuote("NFLX", 200.0, NOW, "yahoo")},
                        chainlink_fn=lambda f, r: {}, dex=amm)
    assert opps[0].best is None and not opps[0].actionable
    cfg["only_buy_token"] = False
    opps = scanner.scan(cfg, tokens_fn=lambda: [tok],
                        wallstreet_fn=lambda s, src: {"NFLX": RefQuote("NFLX", 200.0, NOW, "yahoo")},
                        chainlink_fn=lambda f, r: {}, dex=FakeAMM(1000, 210_000))
    assert opps[0].best is not None and opps[0].best.direction == sizing.SELL_TOKEN

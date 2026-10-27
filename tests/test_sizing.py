from datetime import datetime, timezone

import pytest

from tokenarb import sizing
from tokenarb.market_hours import session_at
from tokenarb.models import OrderBook

NO_COST = sizing.CostModel(token_fee_bps=0, stock_half_spread_bps=0)


def test_direction():
    book = OrderBook(bids=[(99, 1)], asks=[(99.5, 1)])
    assert sizing.direction_for(book, 100) == sizing.BUY_TOKEN
    book = OrderBook(bids=[(101, 1)], asks=[(101.5, 1)])
    assert sizing.direction_for(book, 100) == sizing.SELL_TOKEN
    book = OrderBook(bids=[(99.9, 1)], asks=[(100.1, 1)])
    assert sizing.direction_for(book, 100) is None


def test_evaluate_walks_book():
    book = OrderBook(asks=[(98, 1), (99, 1), (101, 5)])
    r = sizing.evaluate(book, sizing.BUY_TOKEN, 100, 2, NO_COST)
    assert r.token_vwap == pytest.approx(98.5)
    assert r.gross_profit_usd == pytest.approx(3.0)
    assert r.levels_used == 2


def test_optimal_stops_where_book_crosses_reference():
    # livelli sotto 100 profittevoli, quello a 101 no -> ottimo = 2 token
    book = OrderBook(asks=[(98, 1), (99, 1), (101, 5)])
    r = sizing.optimal(book, sizing.BUY_TOKEN, 100, NO_COST, 1e9)
    assert r.qty == pytest.approx(2)
    assert r.net_profit_usd == pytest.approx(3.0)


def test_optimal_respects_capital_cap():
    book = OrderBook(asks=[(90, 100)])
    r = sizing.optimal(book, sizing.BUY_TOKEN, 100, NO_COST, 900)
    assert r.notional_usd == pytest.approx(900)
    assert r.net_profit_usd == pytest.approx(100)


def test_fees_reduce_profit():
    book = OrderBook(bids=[(102, 10)])
    cm = sizing.CostModel(token_fee_bps=50, stock_fee_per_share=0.01,
                          stock_half_spread_bps=10, fixed_cost_usd=1)
    r = sizing.evaluate(book, sizing.SELL_TOKEN, 100, 10, cm)
    # lordo: 1020 - 10*100.1 = 19 ; costi: 5.10 + 0.10 + 1 = 6.20
    assert r.gross_profit_usd == pytest.approx(19.0)
    assert r.fees_usd == pytest.approx(6.2)
    assert r.net_profit_usd == pytest.approx(12.8)


def test_insufficient_liquidity():
    book = OrderBook(asks=[(98, 1)])
    assert sizing.evaluate(book, sizing.BUY_TOKEN, 100, 5, NO_COST) is None


@pytest.mark.parametrize("utc,expected", [
    ("2026-10-27T14:00:00", "regular"),   # 10:00 NY
    ("2026-10-27T12:00:00", "pre"),       # 08:00 NY
    ("2026-10-27T21:00:00", "post"),      # 17:00 NY
    ("2026-10-28T02:00:00", "closed"),    # 22:00 NY
    ("2026-10-31T15:00:00", "closed"),    # sabato
    ("2026-11-26T15:00:00", "closed"),    # Thanksgiving
])
def test_sessions(utc, expected):
    dt = datetime.fromisoformat(utc).replace(tzinfo=timezone.utc)
    assert session_at(dt) == expected

from tokenarb.dexscreener import USDC, best_usdc_pool, parse_pairs

TOK = "0xb200000000000000000000c2e324d24d7eecd1fb"


def _pair(base, quote, price_usd, price_native, dex="aerodrome", liq=1000.0, labels=None):
    return {"chainId": "base", "dexId": dex, "labels": labels or [], "pairAddress": "0xp" + dex,
            "baseToken": {"address": base}, "quoteToken": {"address": quote},
            "priceUsd": str(price_usd), "priceNative": str(price_native), "liquidity": {"usd": liq}}


def test_token_as_base():
    p = parse_pairs([_pair(TOK, USDC, 341.2, 341.1)], [TOK])[0]
    assert p.price_usd == 341.2 and p.is_usdc and p.is_aero


def test_token_as_quote():
    # base = USDC (1 $), quote = token: priceNative = token per USDC
    p = parse_pairs([_pair(USDC, TOK, 1.0, 1 / 341.0)], [TOK])[0]
    assert abs(p.price_usd - 341.0) < 1e-6 and p.token == TOK.lower()


def test_prefers_aero_then_liquidity():
    pools = parse_pairs([
        _pair(TOK, USDC, 340, 340, dex="uniswap", liq=900_000),
        _pair(TOK, USDC, 341, 341, dex="aerodrome", liq=500_000, labels=["v3"]),
        _pair(TOK, USDC, 342, 342, dex="aerodrome", liq=1_000),
        _pair(TOK, "0xweth", 341, 0.1, dex="aerodrome", liq=9_000_000),
    ], [TOK])
    b = best_usdc_pool(pools)
    assert b.dex == "aerodrome v3" and b.liquidity_usd == 500_000


def test_other_chain_ignored():
    p = _pair(TOK, USDC, 341, 341)
    p["chainId"] = "ethereum"
    assert parse_pairs([p], [TOK]) == []

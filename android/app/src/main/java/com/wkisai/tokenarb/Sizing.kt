package com.wkisai.tokenarb

/**
 * Size ottimale e profitto netto con quote on-chain reali.
 *  token sotto prezzo -> compra token con USDC su Base + short dell'azione
 *  token sopra prezzo -> vendi token per USDC + compra l'azione
 * Su un AMM il profitto sale, tocca un massimo e poi scende: si prova una scala
 * di size, ci si ferma quando cala e si raffina attorno al massimo.
 */
object Sizing {
    const val BUY = "buy_token_sell_stock"
    const val SELL = "sell_token_buy_stock"

    data class Costs(
        val stockFeeBps: Double,
        val stockFeePerShare: Double,
        val halfSpreadBps: Double,
        val fixedUsd: Double,
        val multiplier: Double,
    )

    /** BUY: amountIn in USDC -> token. SELL: amountIn in token -> USDC. */
    fun interface QuoteFn {
        fun quote(direction: String, amountIn: Double): DexQuote
    }

    fun evaluate(direction: String, notional: Double, fair: Double, q: QuoteFn, c: Costs): SizeResult? {
        val hs = c.halfSpreadBps / 1e4
        // (token, lordo, prezzo esecuzione, prezzo azione, gas)
        val leg: DoubleArray = try {
            if (direction == BUY) {
                val r = q.quote(BUY, notional)
                val tk = r.amountOut
                if (tk <= 0) return null
                val px = fair * (1 - hs)                 // short dell'azione al bid
                doubleArrayOf(tk, tk * px - notional, notional / tk, px, r.gasUsd)
            } else {
                val tk = notional / fair
                val r = q.quote(SELL, tk)
                val px = fair * (1 + hs)                 // acquisto dell'azione all'ask
                doubleArrayOf(tk, r.amountOut - tk * px, r.amountOut / tk, px, r.gasUsd)
            }
        } catch (e: Exception) {
            return null
        }
        val tokens = leg[0]
        val gross = leg[1]
        val exec = leg[2]
        val stockPx = leg[3]
        val gas = leg[4]
        val costs = gas + tokens * stockPx * c.stockFeeBps / 1e4 +
            tokens * c.multiplier * c.stockFeePerShare + c.fixedUsd
        val net = gross - costs
        val value = tokens * exec
        return SizeResult(direction, value, tokens, exec, fair, gross, costs, net,
            if (value > 0) net / value * 1e4 else 0.0)
    }

    fun optimal(direction: String, fair: Double, q: QuoteFn, c: Costs,
                ladder: List<Double>, maxNotional: Double): SizeResult? {
        val sizes = (ladder.filter { it <= maxNotional } + maxNotional).distinct().sorted()
        val results = mutableListOf<SizeResult>()
        for (s in sizes) {
            val r = evaluate(direction, s, fair, q, c) ?: break
            results += r
            val n = results.size
            if (n >= 3 && results[n - 1].netUsd < results[n - 2].netUsd &&
                results[n - 2].netUsd < results[n - 3].netUsd) break
        }
        if (results.isEmpty()) return null
        val i = results.indices.maxByOrNull { results[it].netUsd }!!
        var best = results[i]
        for (j in listOf(i - 1, i + 1)) {
            if (j in results.indices) {
                val mid = (sizes[i] + sizes[j]) / 2
                val r = evaluate(direction, mid, fair, q, c)
                if (r != null && r.netUsd > best.netUsd) best = r
            }
        }
        return best.copy(ladder = results.map { it.notionalUsd to it.netUsd })
    }

    fun direction(buyPx: Double, sellPx: Double, fair: Double): String? = when {
        buyPx < fair -> BUY
        sellPx > fair -> SELL
        else -> null
    }
}

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
                ladder: List<Double>, maxNotional: Double): SizeResult? =
        search(direction, fair, q, c, ladder, maxNotional, null).first

    /**
     * Sale lungo la scala di size finché il netto cresce, poi raffina attorno al massimo.
     * Se la size fissa di confronto è nella scala ne riusa la quota. -> (ottimo, size fissa)
     */
    fun search(direction: String, fair: Double, q: QuoteFn, c: Costs, ladder: List<Double>,
               maxNotional: Double, fixedSize: Double?): Pair<SizeResult?, SizeResult?> {
        val extra = listOfNotNull(fixedSize?.takeIf { it <= maxNotional })
        val sizes = (ladder.filter { it <= maxNotional } + maxNotional + extra).distinct().sorted()
        val results = mutableListOf<SizeResult>()
        val sizeOf = mutableListOf<Double>()
        for (s in sizes) {
            val r = evaluate(direction, s, fair, q, c) ?: break
            results += r
            sizeOf += s
            val n = results.size
            if (n >= 3 && results[n - 1].netUsd < results[n - 2].netUsd &&
                results[n - 2].netUsd < results[n - 3].netUsd) break
        }
        val fixed: SizeResult? = when {
            fixedSize == null -> null
            sizeOf.indexOf(fixedSize) >= 0 -> results[sizeOf.indexOf(fixedSize)]
            else -> evaluate(direction, fixedSize, fair, q, c)
        }
        if (results.isEmpty()) return Pair(null, fixed)
        val i = results.indices.maxByOrNull { results[it].netUsd }!!
        var best = results[i]
        for (j in listOf(i - 1, i + 1)) {
            if (j in results.indices) {
                val r = evaluate(direction, (sizeOf[i] + sizeOf[j]) / 2, fair, q, c)
                if (r != null && r.netUsd > best.netUsd) best = r
            }
        }
        return Pair(best.copy(ladder = results.map { it.notionalUsd to it.netUsd }), fixed)
    }

    /** Margine per token a size minima al netto dei costi di copertura (gas escluso). */
    fun unitEdge(direction: String, buyPx: Double, sellPx: Double, fair: Double, c: Costs): Double {
        val hs = c.halfSpreadBps / 1e4
        return if (direction == BUY) {
            val stockPx = fair * (1 - hs)
            stockPx * (1 - c.stockFeeBps / 1e4) - c.multiplier * c.stockFeePerShare - buyPx
        } else {
            val stockPx = fair * (1 + hs)
            sellPx - stockPx * (1 + c.stockFeeBps / 1e4) - c.multiplier * c.stockFeePerShare
        }
    }

    fun direction(buyPx: Double, sellPx: Double, fair: Double): String? = when {
        buyPx < fair -> BUY
        sellPx > fair -> SELL
        else -> null
    }
}

package com.wkisai.tokenarb

import java.time.Instant
import kotlin.math.abs

/** Una scansione completa: token Coinbase su Base vs Wall Street. */
object Scanner {
    private val LADDER = listOf(250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0, 50000.0)
    private const val PROBE_USD = 100.0
    private const val CHAINLINK_DIVERGENCE_BPS = 100.0

    /** Feed Chainlink "Base Tokenized Equities" (prezzo per token). */
    private val FEEDS = mapOf(
        "AAPL" to "0x787f13dEa48Db0897CbCDD985de77809D837F988",
        "AMZN" to "0x06A8E4b3aBB3B7543d8396FB2B763d22820cB295",
        "GOOGL" to "0x5bF49E0ffA937CE2FfF033c739aD7C634c4D34F2",
        "META" to "0x6526aE6797A76123638b863AeE4dD27Ba4E4b27D",
        "MSFT" to "0xeB10A6c9aa7E537aEd766C08c35Dae35B321b18c",
        "MSTR" to "0xB3cE282CD188b35DA0E38D8Bc7d58e33173D202a",
        "NVDA" to "0x04689a41629776563E6822F76f2e57D148d28513",
        "SNDK" to "0x388b0dC46C0Fb05A74BeE0994fa5b02c6Fcca2eA",
        "SPCX" to "0x6A634B235903C4ad6376892180d6fF8612e3Fa68",
        "TSLA" to "0xFaf869185383a24F8cb00e27BdA6b63B9905DCb4",
    )

    fun yahooSymbol(ticker: String) = ticker.replace('.', '-').replace('/', '-')

    fun run(s: Settings, progress: (String) -> Unit = {}): ScanOutcome {
        val now = System.currentTimeMillis()
        val session = Market.session(Instant.ofEpochMilli(now))
        val tradable = if (s.extendedTradable) setOf("regular", "pre", "post") else setOf("regular")
        val errors = mutableListOf<String>()

        progress("Scarico l'elenco token Coinbase…")
        var tokens = Api.listTokens()
        val total = tokens.size
        val wanted = s.tickerSet()
        val excluded = s.excludeSet()
        if (wanted.isNotEmpty()) tokens = tokens.filter { it.ticker.uppercase() in wanted }
        tokens = tokens.filter { it.ticker.uppercase() !in excluded }

        progress("Prezzi Wall Street (${tokens.size} titoli)…")
        val ws = mutableMapOf<String, RefQuote>()
        for (t in tokens) {
            try {
                Api.yahoo(t.ticker, yahooSymbol(t.ticker))?.let { ws[t.ticker] = it }
            } catch (e: Exception) {
                errors += "Yahoo ${t.ticker}: ${e.message}"
            }
        }
        val cl = try {
            Api.chainlink(FEEDS.filterKeys { k -> tokens.any { it.ticker == k } })
        } catch (e: Exception) {
            errors += "Chainlink: ${e.message}"
            emptyMap()
        }

        val opps = mutableListOf<Opportunity>()
        tokens.forEachIndexed { idx, t ->
            progress("Quote on-chain ${idx + 1}/${tokens.size}: ${t.symbol}")
            val w = ws[t.ticker]
            val c = cl[t.ticker]
            val fair = pickFair(t, w, c, now, s.maxRefAgeMin)
            if (fair == null) {
                errors += "${t.symbol}: nessun prezzo di riferimento"
                return@forEachIndexed
            }
            val (fairPx, src, age) = fair
            val q = Sizing.QuoteFn { dir, amt ->
                if (dir == Sizing.BUY) Api.quote(Api.USDC, Api.USDC_DECIMALS, t.address, t.decimals, amt)
                else Api.quote(t.address, t.decimals, Api.USDC, Api.USDC_DECIMALS, amt)
            }
            val probe: Pair<Double, Double>? = try {
                val qb = q.quote(Sizing.BUY, PROBE_USD)
                val probeTokens = PROBE_USD / fairPx
                val qs = q.quote(Sizing.SELL, probeTokens)
                Pair(PROBE_USD / qb.amountOut, qs.amountOut / probeTokens)
            } catch (e: Exception) {
                null
            }
            if (probe == null || !probe.first.isFinite() || !probe.second.isFinite()) {
                errors += "${t.symbol}: nessuna liquidità on-chain"
                return@forEachIndexed
            }
            val buyPx = probe.first
            val sellPx = probe.second

            val gap = ((buyPx + sellPx) / 2 - fairPx) / fairPx * 1e4
            val costs = Sizing.Costs(
                s.feeBps, s.feePerShare,
                if (session == "regular") s.halfSpreadRegular else s.halfSpreadExtended,
                s.fixedCost, t.multiplier,
            )
            val dir = Sizing.direction(buyPx, sellPx, fairPx)
            val best = dir?.let { Sizing.optimal(it, fairPx, q, costs, LADDER, s.maxNotional) }
            val fixed = dir?.let { Sizing.evaluate(it, s.tradeSize, fairPx, q, costs) }

            val notes = mutableListOf<String>()
            if (session !in tradable) notes += "Borsa ${Market.label(session)}: gap indicativo, non copribile"
            if (age > s.maxRefAgeMin) notes += "prezzo di riferimento vecchio di ${age.toInt()} min"
            if (t.pausedFeatures.isNotEmpty()) notes += "funzioni del contratto in pausa"
            if (w != null && c != null && (now - c.asOfMillis) / 60000.0 <= s.maxRefAgeMin) {
                val diff = (w.price * t.multiplier - c.price) / c.price * 1e4
                if (abs(diff) > CHAINLINK_DIVERGENCE_BPS) notes += "Yahoo e Chainlink divergono di ${diff.toInt()} bps"
            }
            val actionable = best != null && session in tradable && age <= s.maxRefAgeMin &&
                t.pausedFeatures.isEmpty() && best.netUsd >= s.minProfit && best.netBps >= s.minBps

            opps += Opportunity(t.ticker, t.symbol, t.address, session, fairPx, src, age,
                buyPx, sellPx, gap, c?.price, best, fixed, actionable, notes)
        }
        return ScanOutcome(now, session, total,
            opps.sortedByDescending { it.best?.netUsd ?: -1e9 }, errors)
    }

    /** -> (prezzo per token, fonte, età in minuti). Priorità a Wall Street fresco, poi Chainlink. */
    private fun pickFair(t: CbToken, w: RefQuote?, c: RefQuote?, now: Long, maxAge: Double): Triple<Double, String, Double>? {
        fun age(r: RefQuote) = (now - r.asOfMillis) / 60000.0
        return when {
            w != null && age(w) <= maxAge -> Triple(w.price * t.multiplier, w.source, age(w))
            c != null && age(c) <= maxAge -> Triple(c.price, c.source, age(c))
            w != null -> Triple(w.price * t.multiplier, w.source, age(w))
            c != null -> Triple(c.price, c.source, age(c))
            else -> null
        }
    }
}

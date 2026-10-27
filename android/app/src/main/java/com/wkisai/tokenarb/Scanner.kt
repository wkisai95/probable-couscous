package com.wkisai.tokenarb

import org.json.JSONArray
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * Scansione a ogni giro:
 *  1. DexScreener (pool token/USDC, Aero preferita, ~0,2 s): confronto con Wall Street e
 *     arbitraggi subito a schermo; parte la verifica on-chain (KyberSwap) dei candidati;
 *  2. GeckoTerminal subito dopo: aggiunge i token che DexScreener non indicizza e sostituisce
 *     il prezzo quando trova una pool molto più liquida; i nuovi candidati vanno in verifica;
 *  3. controllo delle posizioni con i prezzi migliori delle due fonti;
 *  4. fine della verifica on-chain: prezzo eseguibile, size ottimale e profitto netto.
 */
object Scanner {
    private val LADDER = listOf(500.0, 1500.0, 4000.0, 10000.0, 25000.0, 50000.0)
    private const val PROBE_USD = 100.0
    private const val MIN_POOL_USD = 1_000.0
    private const val CHAINLINK_DIVERGENCE_BPS = 100.0
    const val MAX_PLAUSIBLE_GAP_BPS = 2_500.0
    const val NO_ROUTE_TTL_MS = 6 * 3_600_000L
    private const val TOKENS_TTL_MS = 3_600_000L
    private const val YAHOO_THREADS = 12
    private const val KYBER_THREADS = 6

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

    /** Stato persistente tra le scansioni. */
    class State(
        var tokensJson: String?,
        var tokensAt: Long,
        val noRoute: MutableMap<String, Long>,
    )

    private class Candidate(val token: CbToken, val fair: Double, val gap: Double, val base: Opportunity)

    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    fun yahooSymbol(ticker: String) = ticker.replace('.', '-').replace('/', '-')

    /** Esegue una scansione; restituisce null se ce n'è già una in corso. */
    fun runExclusive(
        s: Settings, st: State, positions: List<Position>,
        onUpdate: (ScanOutcome) -> Unit = {},
        onPositions: (List<PositionStatus>) -> Unit = {},
    ): ScanOutcome? {
        if (!running.compareAndSet(false, true)) return null
        try {
            return run(s, st, positions, onUpdate, onPositions)
        } finally {
            running.set(false)
        }
    }

    private fun run(
        s: Settings, st: State, positions: List<Position>,
        onUpdate: (ScanOutcome) -> Unit, onPositions: (List<PositionStatus>) -> Unit,
    ): ScanOutcome {
        val started = System.currentTimeMillis()
        val session = Market.session(Instant.ofEpochMilli(started))
        val tradable = if (s.extendedTradable) setOf("regular", "pre", "post") else setOf("regular")
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val feed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        st.noRoute.entries.removeAll { it.value < started }
        val opps = ConcurrentHashMap<String, Opportunity>()
        var total = 0

        fun outcome(inProgress: Boolean, progress: String) = ScanOutcome(
            started, session, total, sortOpps(opps.values), synchronized(notes) { notes.toList() },
            inProgress, progress, ((System.currentTimeMillis() - started) / 1000).toInt(),
            synchronized(feed) { feed.joinToString(" · ") },
        )

        // ---- elenco token (in cache per un'ora)
        onUpdate(outcome(true, "Elenco token Coinbase…"))
        val allTokens = cachedTokens(st, started)
        total = allTokens.size
        val byAddr = allTokens.associateBy { it.address.lowercase() }
        val wanted = s.tickerSet()
        val excluded = s.excludeSet()
        val screenAddr = allTokens.filter {
            (wanted.isEmpty() || it.ticker.uppercase() in wanted) && it.ticker.uppercase() !in excluded
        }.map { it.address.lowercase() }.toSet()
        val posAddr = positions.map { it.address.lowercase() }.toSet()
        val scope = allTokens.filter { val a = it.address.lowercase(); a in screenAddr || a in posAddr }

        // ---- 1. DexScreener
        onUpdate(outcome(true, "Prezzi delle pool Aero (DexScreener)…"))
        val t0 = System.currentTimeMillis()
        val (dsPrices, dsFailed) = Api.dexscreener(scope.map { it.address })
        feed += "DexScreener ${System.currentTimeMillis() - t0} ms"
        if (dsFailed > 0) notes += "DexScreener: $dsFailed richieste fallite"

        // ---- 2. GeckoTerminal parte subito dopo, in parallelo al resto
        val gtStart = System.currentTimeMillis()
        val gtExec = Executors.newSingleThreadExecutor()
        val gtFuture = gtExec.submit<Pair<Map<String, PoolPrice>, Int>> { Api.gecko(scope.map { it.address }) }
        gtExec.shutdown()

        val ws = ConcurrentHashMap<String, RefQuote>()
        val priceBy = ConcurrentHashMap<String, PoolPrice>()
        val fairBy = ConcurrentHashMap<String, Double>()
        val candidates = ConcurrentHashMap.newKeySet<String>()
        val noRef = Collections.synchronizedSet(mutableSetOf<String>())
        val prefilter = max(10.0, s.minBps * 0.5)
        val verifyExec = Executors.newFixedThreadPool(KYBER_THREADS)
        val queued = AtomicInteger(0)
        val done = AtomicInteger(0)
        val lastEmit = AtomicLong(0)

        fun emitProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val prev = lastEmit.get()
            if ((force || now - prev >= 700) && lastEmit.compareAndSet(prev, now)) {
                val gtNote = if (gtFuture.isDone) "" else " · GeckoTerminal in arrivo…"
                onUpdate(outcome(true, "Verifica on-chain: ${done.get()}/${queued.get()}$gtNote"))
            }
        }

        /** Verifica on-chain di un candidato, in parallelo. */
        fun verify(c: Candidate) {
            queued.incrementAndGet()
            verifyExec.execute {
                val a = c.token.address.lowercase()
                opps[a] = try {
                    sizeCandidate(c, s, session, tradable, st, started)
                } catch (e: Exception) {
                    c.base.copy(verified = true, notes = c.base.notes + "verifica non riuscita")
                }
                done.incrementAndGet()
                emitProgress()
            }
        }

        val cl = try {
            Api.chainlink(FEEDS.filterKeys { k -> scope.any { it.ticker == k } })
        } catch (e: Exception) {
            emptyMap()
        }

        /** Confronto con Wall Street per un gruppo di token con i prezzi indicati. */
        fun screen(tokens: List<CbToken>, prices: Map<String, PoolPrice>) {
            for (t in tokens) {
                val a = t.address.lowercase()
                val pool = prices[a] ?: continue
                priceBy[a] = pool
                val w = ws[t.ticker]
                val c = cl[t.ticker]
                val fair = pickFair(t, w, c, started, s.maxRefAgeMin)
                if (fair == null) {
                    noRef += t.symbol
                    continue
                }
                noRef -= t.symbol
                val (fairPx, src, age) = fair
                fairBy[a] = fairPx
                if (a !in screenAddr) continue
                if (candidates.contains(a)) continue   // già in verifica on-chain: decide la verifica
                val gap = (pool.priceUsd - fairPx) / fairPx * 1e4
                val base = Opportunity(
                    ticker = t.ticker, symbol = t.symbol, address = t.address, session = session,
                    fairPrice = fairPx, refSource = src, refAgeMin = age,
                    dexBuy = null, dexSell = null, poolPrice = pool.priceUsd, liquidityUsd = pool.liquidityUsd,
                    dex = pool.dex, pair = pool.pair, gapBps = gap, chainlink = c?.price,
                    best = null, fixed = null, actionable = false, verified = false,
                    notes = baseNotes(t, session, tradable, age, s, w, c, started),
                )
                val relevant = if (s.buyOnly) gap <= -prefilter else abs(gap) >= prefilter
                if (!relevant) {
                    val why = if (s.buyOnly) "sconto inferiore a ${prefilter.toInt()} bps" else "gap sotto ${prefilter.toInt()} bps"
                    opps[a] = base.copy(verified = true, notes = base.notes + why)
                } else if (a in st.noRoute) {
                    opps[a] = base.copy(verified = true,
                        notes = base.notes + "nessun percorso di scambio su KyberSwap (ricontrollo tra qualche ora)")
                } else {
                    candidates.add(a)
                    opps[a] = base
                    verify(Candidate(t, fairPx, gap, base))
                }
            }
        }

        // ---- quadro con i prezzi DexScreener: subito a schermo
        val dsTokens = scope.filter { t ->
            val a = t.address.lowercase()
            val p = dsPrices[a]
            p != null && (p.liquidityUsd >= MIN_POOL_USD || a in posAddr)
        }
        onUpdate(outcome(true, "Prezzi Wall Street (${dsTokens.size} titoli)…"))
        ws.putAll(fetchYahoo(dsTokens))
        screen(dsTokens, dsPrices)
        emitProgress(force = true)

        // ---- GeckoTerminal: completa e migliora i prezzi
        val gt = try {
            gtFuture.get(120, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        if (gt == null) {
            notes += "GeckoTerminal non disponibile in questa scansione: alcuni token potrebbero mancare"
        } else {
            val (gtPrices, gtFailed) = gt
            val sec = (System.currentTimeMillis() - gtStart) / 1000.0
            feed += "GeckoTerminal " + String.format(Locale.ITALY, "%.1f s", sec)
            if (gtFailed > 0) notes += "GeckoTerminal: $gtFailed richieste fallite"
            // pool GeckoTerminal usata se DexScreener non ha il token o ha una pool molto meno liquida
            val better = mutableMapOf<String, PoolPrice>()
            for (t in scope) {
                val a = t.address.lowercase()
                val g = gtPrices[a] ?: continue
                if (g.liquidityUsd < MIN_POOL_USD && a !in posAddr) continue
                val d = dsPrices[a]
                if (d == null || g.liquidityUsd > d.liquidityUsd * 2) better[a] = g
            }
            val gtTokens = scope.filter { it.address.lowercase() in better }
            val needWs = gtTokens.filter { !ws.containsKey(it.ticker) }
            if (needWs.isNotEmpty()) ws.putAll(fetchYahoo(needWs))
            screen(gtTokens, better)
            emitProgress(force = true)
        }

        // ---- posizioni, con i prezzi migliori delle due fonti
        if (positions.isNotEmpty()) {
            onUpdate(outcome(true, "Controllo le tue posizioni…"))
            onPositions(positions.map { p ->
                val a = p.address.lowercase()
                checkPosition(p, byAddr[a], fairBy[a], priceBy[a], session, s, started)
            })
        }

        // ---- fine della verifica on-chain
        verifyExec.shutdown()
        verifyExec.awaitTermination(8, TimeUnit.MINUTES)

        val skipped = screenAddr.count { !priceBy.containsKey(it) }
        val missingRef = synchronized(noRef) { noRef.sorted() }
        if (missingRef.isNotEmpty()) notes += "Senza prezzo di Borsa: ${missingRef.joinToString(", ")}"
        if (skipped > 0) notes += "Senza pool con liquidità su Base: $skipped token (saltati)"
        return outcome(false, "")
    }

    /** Quota eseguibile, margine, size ottimale e profitto netto di un candidato. */
    private fun sizeCandidate(c: Candidate, s: Settings, session: String, tradable: Set<String>,
                              st: State, now: Long): Opportunity {
        val t = c.token
        val fair = c.fair
        val costs = Sizing.Costs(
            s.feeBps, s.feePerShare,
            if (session == "regular") s.halfSpreadRegular else s.halfSpreadExtended,
            s.fixedCost, t.multiplier,
        )
        val q = Sizing.QuoteFn { dir, amt ->
            if (dir == Sizing.BUY) Api.quote(Api.USDC, Api.USDC_DECIMALS, t.address, t.decimals, amt)
            else Api.quote(t.address, t.decimals, Api.USDC, Api.USDC_DECIMALS, amt)
        }
        val dir = if (c.gap < 0) Sizing.BUY else Sizing.SELL
        val px: Double = try {
            if (dir == Sizing.BUY) {
                PROBE_USD / q.quote(Sizing.BUY, PROBE_USD).amountOut
            } else {
                val tk = PROBE_USD / fair
                q.quote(Sizing.SELL, tk).amountOut / tk
            }
        } catch (e: NoRouteException) {
            st.noRoute[t.address.lowercase()] = now + NO_ROUTE_TTL_MS
            return c.base.copy(verified = true, notes = c.base.notes + "nessun percorso di scambio su KyberSwap")
        } catch (e: Exception) {
            return c.base.copy(verified = true, notes = c.base.notes + "quota non disponibile (${e.message?.take(60)})")
        }
        if (!px.isFinite() || px <= 0) {
            return c.base.copy(verified = true, notes = c.base.notes + "quota non valida")
        }
        val buyPx = if (dir == Sizing.BUY) px else null
        val sellPx = if (dir == Sizing.SELL) px else null
        val edge = Sizing.unitEdge(dir, buyPx ?: Double.MAX_VALUE, sellPx ?: 0.0, fair, costs)
        if (edge <= 0) {
            return c.base.copy(dexBuy = buyPx, dexSell = sellPx, verified = true,
                notes = c.base.notes + "il prezzo eseguibile non copre i costi della copertura")
        }
        val (best, fixed) = Sizing.search(dir, fair, q, costs, LADDER, s.maxNotional, s.tradeSize)
        val actionable = best != null && session in tradable && c.base.refAgeMin <= s.maxRefAgeMin &&
            t.pausedFeatures.isEmpty() && best.netUsd >= s.minProfit && best.netBps >= s.minBps
        return c.base.copy(dexBuy = buyPx, dexSell = sellPx, best = best, fixed = fixed,
            actionable = actionable, verified = true)
    }

    /**
     * Quota della posizione vendibile chiudendo il gap: prezzo medio di vendita eseguibile
     * ≥ prezzo di Borsa × (1 − tolleranza). Si cerca la quota più alta a passi del 10%.
     */
    private fun checkPosition(p: Position, t: CbToken?, fair: Double?, pool: PoolPrice?,
                              session: String, s: Settings, now: Long): PositionStatus {
        fun status(fraction: Double = 0.0, sellTokens: Double? = null, proceeds: Double? = null,
                   avg: Double? = null, pnl: Double? = null, note: String? = null) =
            PositionStatus(p.id, now, fair, pool?.priceUsd, fraction, sellTokens, proceeds, avg, pnl, note)

        if (t == null) return status(note = "token non più presente nell'elenco Coinbase")
        if (fair == null) {
            return status(note = if (pool == null) "prezzo on-chain non disponibile in questa scansione"
            else "prezzo di Borsa non disponibile")
        }
        if (p.tokens <= 0) return status(note = "posizione vuota")
        val target = fair * (1 - s.closeTolBps / 1e4)
        val closed = if (session != "regular") "Borsa ${Market.label(session)}: confronto con l'ultimo prezzo" else null
        val spot = pool?.priceUsd
        if (spot != null && spot < target) {
            val gap = (spot - fair) / fair * 1e4
            return status(note = listOfNotNull("gap ancora aperto (${gap.toInt()} bps)", closed).joinToString(" · "))
        }
        var lo = 0
        var hi = 10
        var bestK = 0
        var bestQ: DexQuote? = null
        try {
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val amount = p.tokens * mid / 10.0
                val qd = Api.quote(t.address, t.decimals, Api.USDC, Api.USDC_DECIMALS, amount)
                if (qd.amountOut / amount >= target) {
                    lo = mid
                    bestK = mid
                    bestQ = qd
                } else {
                    hi = mid - 1
                }
            }
        } catch (e: Exception) {
            return status(note = "quota di vendita non disponibile (${e.message?.take(60)})")
        }
        val q = bestQ
        if (bestK == 0 || q == null) {
            return status(note = listOfNotNull("il prezzo di vendita eseguibile non chiude ancora il gap", closed)
                .joinToString(" · "))
        }
        val f = bestK / 10.0
        val sellTokens = p.tokens * f
        val proceeds = q.amountOut - q.gasUsd
        return status(f, sellTokens, proceeds, q.amountOut / sellTokens, proceeds - p.usd * f, closed)
    }

    private fun baseNotes(t: CbToken, session: String, tradable: Set<String>, age: Double, s: Settings,
                          w: RefQuote?, c: RefQuote?, now: Long): List<String> {
        val notes = mutableListOf<String>()
        if (session !in tradable) notes += "Borsa ${Market.label(session)}: gap indicativo, non copribile"
        if (age > s.maxRefAgeMin) notes += "prezzo di riferimento vecchio di ${age.toInt()} min"
        if (t.pausedFeatures.isNotEmpty()) notes += "funzioni del contratto in pausa"
        if (w != null && c != null && (now - c.asOfMillis) / 60000.0 <= s.maxRefAgeMin) {
            val diff = (w.price * t.multiplier - c.price) / c.price * 1e4
            if (abs(diff) > CHAINLINK_DIVERGENCE_BPS) notes += "Yahoo e Chainlink divergono di ${diff.toInt()} bps"
        }
        return notes
    }

    private fun sortOpps(c: Collection<Opportunity>): List<Opportunity> = c.sortedWith(
        compareByDescending<Opportunity> { it.actionable }
            .thenByDescending { it.best?.netUsd ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { abs(it.gapBps) }
    )

    /** Elenco token dall'API Coinbase, in cache per un'ora (se l'API non risponde usa la cache). */
    private fun cachedTokens(st: State, now: Long): List<CbToken> {
        val cached = st.tokensJson?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { CbToken.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                null
            }
        }
        if (cached != null && cached.isNotEmpty() && now - st.tokensAt < TOKENS_TTL_MS) return cached
        return try {
            val fresh = Api.listTokens()
            st.tokensJson = JSONArray().apply { fresh.forEach { put(it.toJson()) } }.toString()
            st.tokensAt = now
            fresh
        } catch (e: Exception) {
            if (cached != null && cached.isNotEmpty()) cached else throw e
        }
    }

    private fun <T> parallel(items: List<T>, threads: Int, block: (T) -> Unit) {
        if (items.isEmpty()) return
        val pool = Executors.newFixedThreadPool(minOf(threads, items.size))
        try {
            for (item in items) {
                pool.execute {
                    try {
                        block(item)
                    } catch (e: Exception) {
                        // ogni blocco gestisce i propri errori; qui si evita solo di perdere il thread
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(8, TimeUnit.MINUTES)
        }
    }

    /** Prezzi Yahoo in parallelo. */
    private fun fetchYahoo(tokens: List<CbToken>): Map<String, RefQuote> {
        val out = ConcurrentHashMap<String, RefQuote>()
        parallel(tokens.distinctBy { it.ticker }, YAHOO_THREADS) { t ->
            try {
                Api.yahoo(t.ticker, yahooSymbol(t.ticker))?.let { out[t.ticker] = it }
            } catch (e: Exception) {
                // ticker senza dati: finisce tra i "senza prezzo di Borsa"
            }
        }
        return out
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

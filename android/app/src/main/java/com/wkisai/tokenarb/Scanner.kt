package com.wkisai.tokenarb

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * Scansione in tre fasi:
 *  1. prezzi delle pool token/USDC (DexScreener, Aero preferita: ~0,2 s per tutti i token);
 *     i token con liquidità che DexScreener non indicizza (scoperti con GeckoTerminal ogni 6 ore,
 *     in background) vengono prezzati con una quota KyberSwap diretta;
 *  2. confronto con Wall Street: gli arbitraggi vanno subito a schermo, poi si controllano le posizioni;
 *  3. verifica on-chain (KyberSwap, in parallelo) di prezzo eseguibile, size ottimale e profitto netto.
 */
object Scanner {
    private val LADDER = listOf(500.0, 1500.0, 4000.0, 10000.0, 25000.0, 50000.0)
    private const val PROBE_USD = 100.0
    private const val MIN_POOL_USD = 1_000.0
    private const val CHAINLINK_DIVERGENCE_BPS = 100.0
    const val MAX_PLAUSIBLE_GAP_BPS = 2_500.0
    const val NO_ROUTE_TTL_MS = 6 * 3_600_000L
    private const val TOKENS_TTL_MS = 3_600_000L
    private const val DISCOVERY_TTL_MS = 6 * 3_600_000L
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
        var discoveryJson: String?,
        var discoveryAt: Long,
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
        st.noRoute.entries.removeAll { it.value < started }
        val opps = ConcurrentHashMap<String, Opportunity>()
        var total = 0
        var feedInfo = ""

        fun outcome(inProgress: Boolean, progress: String) = ScanOutcome(
            started, session, total, sortOpps(opps.values), synchronized(notes) { notes.toList() },
            inProgress, progress, ((System.currentTimeMillis() - started) / 1000).toInt(), feedInfo,
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

        // ---- scoperta delle pool non indicizzate da DexScreener (in background, ogni 6 ore)
        val cachedDiscovery = parseDiscovery(st.discoveryJson)
        val discoveryStale = cachedDiscovery.isEmpty() || started - st.discoveryAt > DISCOVERY_TTL_MS
        val bg = Executors.newSingleThreadExecutor()
        val discovery: Future<Map<String, PoolPrice>>? =
            if (discoveryStale) bg.submit<Map<String, PoolPrice>> { Api.gecko(allTokens.map { it.address }) } else null
        bg.shutdown()

        // ---- 1. prezzi DexScreener
        onUpdate(outcome(true, "Prezzi delle pool Aero (DexScreener)…"))
        val t0 = System.currentTimeMillis()
        val (dsPrices, dsFailed) = Api.dexscreener(scope.map { it.address })
        feedInfo = "DexScreener ${System.currentTimeMillis() - t0} ms"
        if (dsFailed > 0) notes += "DexScreener: $dsFailed richieste fallite, alcuni token potrebbero mancare"

        val fairBy = ConcurrentHashMap<String, Double>()
        val priceBy = ConcurrentHashMap<String, PoolPrice>()
        val checked = mutableSetOf<String>()
        val noRef = Collections.synchronizedList(mutableListOf<String>())

        /** Token da prezzare con una quota diretta: liquidi altrove ma assenti o marginali su DexScreener. */
        fun extrasFrom(disc: Map<String, PoolPrice>): Map<String, PoolPrice> = disc.filter { (a, g) ->
            val ds = dsPrices[a]
            g.liquidityUsd >= MIN_POOL_USD && (ds == null || ds.liquidityUsd < g.liquidityUsd / 5)
        }

        /** Confronto, posizioni e verifica on-chain per un gruppo di token. */
        fun pass(tokens: List<CbToken>, extras: Map<String, PoolPrice>) {
            if (tokens.isEmpty()) return
            // a) prezzi: DexScreener, oppure quota KyberSwap per i token "extra" (in parallelo con Yahoo)
            val prices = ConcurrentHashMap<String, PoolPrice>()
            for (t in tokens) {
                val a = t.address.lowercase()
                if (a !in extras) dsPrices[a]?.let { prices[a] = it }
            }
            val probeExec = Executors.newFixedThreadPool(KYBER_THREADS)
            for (t in tokens.filter { it.address.lowercase() in extras }) {
                probeExec.execute {
                    val a = t.address.lowercase()
                    val info = extras[a]
                    if (info != null && a !in st.noRoute) {
                        try {
                            val q = Api.quote(Api.USDC, Api.USDC_DECIMALS, t.address, t.decimals, PROBE_USD)
                            if (q.amountOut > 0) {
                                prices[a] = PoolPrice(PROBE_USD / q.amountOut, info.liquidityUsd,
                                    "${info.dex} · quota KyberSwap", info.pair)
                            }
                        } catch (e: NoRouteException) {
                            st.noRoute[a] = started + NO_ROUTE_TTL_MS
                        } catch (e: Exception) {
                            notes += "${t.symbol}: quota non disponibile (${e.message?.take(60)})"
                        }
                    }
                }
            }
            probeExec.shutdown()
            onUpdate(outcome(true, "Prezzi Wall Street (${tokens.size} titoli)…"))
            val ws = fetchYahoo(tokens)
            val cl = try {
                Api.chainlink(FEEDS.filterKeys { k -> tokens.any { it.ticker == k } })
            } catch (e: Exception) {
                emptyMap()
            }
            probeExec.awaitTermination(90, TimeUnit.SECONDS)

            // b) confronto immediato: gli arbitraggi vanno subito a schermo
            val prefilter = max(10.0, s.minBps * 0.5)
            val candidates = mutableListOf<Candidate>()
            for (t in tokens) {
                val a = t.address.lowercase()
                val pool = prices[a] ?: continue
                val w = ws[t.ticker]
                val c = cl[t.ticker]
                val fair = pickFair(t, w, c, started, s.maxRefAgeMin)
                if (fair == null) {
                    noRef += t.symbol
                    continue
                }
                val (fairPx, src, age) = fair
                fairBy[a] = fairPx
                priceBy[a] = pool
                if (a !in screenAddr) continue
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
                    candidates += Candidate(t, fairPx, gap, base)
                    opps[a] = base
                }
            }
            onUpdate(outcome(true, "Verifica on-chain: 0/${candidates.size}"))

            // c) posizioni aperte su questi token
            val here = tokens.map { it.address.lowercase() }.toSet()
            val posHere = positions.filter { it.address.lowercase() in here && it.address.lowercase() !in checked }
            if (posHere.isNotEmpty()) {
                val statuses = posHere.map { p ->
                    val a = p.address.lowercase()
                    checkPosition(p, byAddr[a], fairBy[a], priceBy[a], session, s, started)
                }
                checked += posHere.map { it.address.lowercase() }
                onPositions(statuses)
            }

            // d) verifica on-chain della size, in parallelo
            val done = AtomicInteger(0)
            val lastEmit = AtomicLong(0)
            parallel(candidates, KYBER_THREADS) { cand ->
                opps[cand.token.address.lowercase()] = sizeCandidate(cand, s, session, tradable, st, started)
                val n = done.incrementAndGet()
                val now = System.currentTimeMillis()
                val prev = lastEmit.get()
                if (now - prev >= 700 && lastEmit.compareAndSet(prev, now)) {
                    onUpdate(outcome(true, "Verifica on-chain: $n/${candidates.size}"))
                }
            }
        }

        // ---- passaggio principale: token con pool DexScreener liquida + extra già noti
        val extras1 = extrasFrom(cachedDiscovery)
        val main = scope.filter { t ->
            val a = t.address.lowercase()
            val p = dsPrices[a]
            a in extras1 || (p != null && (p.liquidityUsd >= MIN_POOL_USD || a in posAddr))
        }
        pass(main, extras1)

        // ---- passaggio tardivo: token liquidi scoperti ora (solo quando la cache è scaduta)
        if (discovery != null) {
            onUpdate(outcome(true, "Cerco pool non indicizzate da DexScreener…"))
            val fresh = try {
                discovery.get(90, TimeUnit.SECONDS)
            } catch (e: Exception) {
                null
            }
            if (fresh != null && fresh.isNotEmpty()) {
                st.discoveryJson = serializeDiscovery(fresh)
                st.discoveryAt = System.currentTimeMillis()
                val done = main.map { it.address.lowercase() }.toSet()
                val extras2 = extrasFrom(fresh).filterKeys { it !in done }
                pass(scope.filter { it.address.lowercase() in extras2 }, extras2)
            } else {
                notes += "Scoperta delle pool non riuscita: riprovo alla prossima scansione"
            }
        }

        // ---- posizioni senza prezzo
        val missing = positions.filter { it.address.lowercase() !in checked }
        if (missing.isNotEmpty()) {
            onPositions(missing.map { p ->
                PositionStatus(p.id, started, null, null, 0.0, null, null, null, null,
                    "prezzo on-chain non disponibile in questa scansione")
            })
        }

        val withPrice = priceBy.keys.count { it in screenAddr }
        val skipped = screenAddr.size - withPrice
        if (noRef.isNotEmpty()) notes += "Senza prezzo di Borsa: ${noRef.joinToString(", ")}"
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
        if (fair == null) return status(note = "prezzo di Borsa non disponibile")
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

    private fun serializeDiscovery(m: Map<String, PoolPrice>): String {
        val o = JSONObject()
        for ((k, v) in m) {
            if (v.liquidityUsd < MIN_POOL_USD) continue
            o.put(k, JSONObject().putD("p", v.priceUsd).putD("l", v.liquidityUsd)
                .put("d", v.dex).put("a", v.pair ?: ""))
        }
        return o.toString()
    }

    private fun parseDiscovery(json: String?): Map<String, PoolPrice> {
        if (json.isNullOrEmpty()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = mutableMapOf<String, PoolPrice>()
            o.keys().forEach { k ->
                val v = o.getJSONObject(k)
                out[k] = PoolPrice(v.optDouble("p", 0.0), v.optDouble("l", 0.0), v.optString("d"),
                    v.optString("a").ifEmpty { null })
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

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

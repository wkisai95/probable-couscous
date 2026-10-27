package com.wkisai.tokenarb

import org.json.JSONArray
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * Scansione veloce in due fasi:
 *  1. quadro di tutti i token in pochi secondi: prezzi medi dei pool (GeckoTerminal,
 *     1 richiesta ogni 30 token) contro i prezzi di Borsa (Yahoo, in parallelo);
 *  2. quote eseguibili KyberSwap, in parallelo, solo per i token con un gap rilevante:
 *     direzione, margine, size ottimale e profitto netto.
 * I risultati vengono pubblicati man mano che arrivano.
 */
object Scanner {
    private val LADDER = listOf(500.0, 1500.0, 4000.0, 10000.0, 25000.0, 50000.0)
    private const val PROBE_USD = 100.0
    private const val MIN_POOL_USD = 1_000.0
    private const val CHAINLINK_DIVERGENCE_BPS = 100.0
    const val NO_ROUTE_TTL_MS = 6 * 3_600_000L
    private const val TOKENS_TTL_MS = 3_600_000L
    private const val YAHOO_THREADS = 8
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

    /** Stato persistente tra le scansioni: elenco token in cache e token senza liquidità. */
    class State(
        var tokensJson: String?,
        var tokensAt: Long,
        val noRoute: MutableMap<String, Long>,
    )

    private class Candidate(
        val token: CbToken,
        val fair: Double,
        val gap: Double,
        val probe: Pair<Double, Double>?,   // (acquisto, vendita) se già quotati
        val base: Opportunity,
    )

    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    fun yahooSymbol(ticker: String) = ticker.replace('.', '-').replace('/', '-')

    /** Esegue una scansione; restituisce null se ce n'è già una in corso. */
    fun runExclusive(s: Settings, st: State, onUpdate: (ScanOutcome) -> Unit = {}): ScanOutcome? {
        if (!running.compareAndSet(false, true)) return null
        try {
            return run(s, st, onUpdate)
        } finally {
            running.set(false)
        }
    }

    private fun run(s: Settings, st: State, onUpdate: (ScanOutcome) -> Unit): ScanOutcome {
        val started = System.currentTimeMillis()
        val session = Market.session(Instant.ofEpochMilli(started))
        val tradable = if (s.extendedTradable) setOf("regular", "pre", "post") else setOf("regular")
        val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        st.noRoute.entries.removeAll { it.value < started }
        val opps = ConcurrentHashMap<String, Opportunity>()
        var total = 0

        fun outcome(inProgress: Boolean, progress: String) = ScanOutcome(
            started, session, total, sortOpps(opps.values), synchronized(notes) { notes.toList() },
            inProgress, progress, ((System.currentTimeMillis() - started) / 1000).toInt(),
        )

        // 1. elenco token Coinbase (in cache per un'ora)
        onUpdate(outcome(true, "Elenco token Coinbase…"))
        var tokens = cachedTokens(st, started)
        total = tokens.size
        val wanted = s.tickerSet()
        val excluded = s.excludeSet()
        if (wanted.isNotEmpty()) tokens = tokens.filter { it.ticker.uppercase() in wanted }
        tokens = tokens.filter { it.ticker.uppercase() !in excluded }

        // 2. liquidità e prezzo medio dei pool
        onUpdate(outcome(true, "Prezzi dei pool on-chain…"))
        val pools: Map<String, PoolData>? = try {
            Api.gecko(tokens.map { it.address })
        } catch (e: Exception) {
            notes += "GeckoTerminal non disponibile (${e.message?.take(80)}): quote complete, più lente"
            null
        }
        val liquid = mutableListOf<CbToken>()
        val unknown = mutableListOf<CbToken>()
        var thin = 0
        var skippedNoRoute = 0
        for (t in tokens) {
            val a = t.address.lowercase()
            val p = pools?.get(a)
            when {
                p != null && p.reserveUsd >= MIN_POOL_USD && p.priceUsd != null -> liquid += t
                a in st.noRoute -> skippedNoRoute++
                p != null && p.hasPools -> thin++
                else -> unknown += t
            }
        }

        // 3. token non indicizzati: scoperta via KyberSwap (solo la prima volta, poi in cache)
        val probes = ConcurrentHashMap<String, Pair<Double, Double>>()
        val newNoRoute = AtomicInteger(0)
        if (unknown.isNotEmpty()) {
            onUpdate(outcome(true, "Verifico la liquidità di ${unknown.size} token…"))
            parallel(unknown, KYBER_THREADS) { t ->
                val a = t.address.lowercase()
                try {
                    val qb = Api.quote(Api.USDC, Api.USDC_DECIMALS, t.address, t.decimals, PROBE_USD)
                    if (qb.amountOut <= 0) throw NoRouteException("quota nulla")
                    val qs = Api.quote(t.address, t.decimals, Api.USDC, Api.USDC_DECIMALS, qb.amountOut)
                    probes[a] = Pair(PROBE_USD / qb.amountOut, qs.amountOut / qb.amountOut)
                } catch (e: NoRouteException) {
                    st.noRoute[a] = started + NO_ROUTE_TTL_MS
                    newNoRoute.incrementAndGet()
                } catch (e: Exception) {
                    notes += "${t.symbol}: quota non disponibile (${e.message?.take(80)})"
                }
            }
        }
        val active = liquid + unknown.filter { probes.containsKey(it.address.lowercase()) }

        // 4. prezzi di Borsa per i soli token con liquidità
        onUpdate(outcome(true, "Prezzi Wall Street (${active.size} titoli)…"))
        val ws = fetchYahoo(active)
        val cl = try {
            Api.chainlink(FEEDS.filterKeys { k -> active.any { it.ticker == k } })
        } catch (e: Exception) {
            notes += "Chainlink non disponibile"
            emptyMap()
        }

        // 5. quadro immediato e selezione dei candidati
        val prefilter = max(10.0, s.minBps * 0.5)
        val candidates = mutableListOf<Candidate>()
        val noRef = mutableListOf<String>()
        for (t in active) {
            val a = t.address.lowercase()
            val w = ws[t.ticker]
            val c = cl[t.ticker]
            val fair = pickFair(t, w, c, started, s.maxRefAgeMin)
            if (fair == null) {
                noRef += t.symbol
                continue
            }
            val (fairPx, src, age) = fair
            val pr = probes[a]
            val pool = pools?.get(a)
            val mid = pr?.let { (it.first + it.second) / 2 } ?: pool?.priceUsd ?: continue
            val gap = (mid - fairPx) / fairPx * 1e4
            val base = Opportunity(
                t.ticker, t.symbol, t.address, session, fairPx, src, age,
                pr?.first, pr?.second, pool?.priceUsd, pool?.reserveUsd, gap, c?.price,
                null, null, false, baseNotes(t, session, tradable, age, s, w, c, started),
            )
            if (abs(gap) >= prefilter) {
                candidates += Candidate(t, fairPx, gap, pr, base)
                opps[a] = base
            } else {
                opps[a] = base.copy(notes = base.notes + "gap sotto ${prefilter.toInt()} bps: size non calcolata")
            }
        }
        if (noRef.isNotEmpty()) notes += "Senza prezzo di Borsa: ${noRef.joinToString(", ")}"
        onUpdate(outcome(true, "Calcolo size: 0/${candidates.size} token con gap rilevante"))

        // 6. size e profitto per i candidati, in parallelo
        val done = AtomicInteger(0)
        val lastEmit = AtomicLong(0)
        parallel(candidates, KYBER_THREADS) { cand ->
            opps[cand.token.address.lowercase()] = sizeCandidate(cand, s, session, tradable)
            val n = done.incrementAndGet()
            val now = System.currentTimeMillis()
            val prev = lastEmit.get()
            if (now - prev >= 800 && lastEmit.compareAndSet(prev, now)) {
                onUpdate(outcome(true, "Calcolo size: $n/${candidates.size} token con gap rilevante"))
            }
        }

        val routeInfo = newNoRoute.get() + skippedNoRoute + thin
        if (routeInfo > 0) notes += "Senza liquidità utile on-chain: $routeInfo token (saltati)"
        return outcome(false, "")
    }

    /** Quote eseguibili, direzione, margine e size ottimale di un candidato. */
    private fun sizeCandidate(c: Candidate, s: Settings, session: String, tradable: Set<String>): Opportunity {
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
        // (direzione, prezzo di acquisto, prezzo di vendita) a size piccola
        val probe: Triple<String, Double?, Double?>? = try {
            val known = c.probe
            when {
                known != null -> Sizing.direction(known.first, known.second, fair)
                    ?.let { Triple(it, known.first, known.second) }
                c.gap < 0 -> Triple(Sizing.BUY, PROBE_USD / q.quote(Sizing.BUY, PROBE_USD).amountOut, null)
                else -> {
                    val tk = PROBE_USD / fair
                    Triple(Sizing.SELL, null, q.quote(Sizing.SELL, tk).amountOut / tk)
                }
            }
        } catch (e: NoRouteException) {
            return c.base.copy(notes = c.base.notes + "nessun percorso di scambio su KyberSwap")
        } catch (e: Exception) {
            return c.base.copy(notes = c.base.notes + "quota non disponibile (${e.message?.take(60)})")
        }
        if (probe == null) return c.base.copy(notes = c.base.notes + "prezzi on-chain allineati alla Borsa")
        val (dir, buyPx, sellPx) = probe
        val edge = Sizing.unitEdge(dir, buyPx ?: Double.MAX_VALUE, sellPx ?: 0.0, fair, costs)
        if (edge <= 0) {
            return c.base.copy(dexBuy = buyPx, dexSell = sellPx,
                notes = c.base.notes + "il margine non copre i costi della copertura")
        }
        val (best, fixed) = Sizing.search(dir, fair, q, costs, LADDER, s.maxNotional, s.tradeSize)
        val actionable = best != null && session in tradable && c.base.refAgeMin <= s.maxRefAgeMin &&
            t.pausedFeatures.isEmpty() && best.netUsd >= s.minProfit && best.netBps >= s.minBps
        return c.base.copy(dexBuy = buyPx, dexSell = sellPx, best = best, fixed = fixed, actionable = actionable)
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

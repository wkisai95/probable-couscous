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
import kotlin.math.min

/**
 * Scansione degli stock token Coinbase (Base) e Robinhood (Robinhood Chain) contro Wall Street.
 *  1. prezzi di Borsa: bid/ask Robinhood per tutti i titoli in una richiesta (riserva: Yahoo);
 *  2. DexScreener (pool principale di ogni token, ~1 s): confronto e arbitraggi subito a schermo,
 *     poi verifica on-chain (KyberSwap) dei candidati, i meno liquidi per primi;
 *  3. GeckoTerminal subito dopo: tutte le pool principali di ogni token, comprese le piccole,
 *     dove si trovano i prezzi sbagliati; i nuovi candidati vanno in verifica;
 *  4. controllo delle posizioni e fine della verifica on-chain.
 * Lo sconto di un token è quello della sua pool più economica (per comprare) o più cara (per vendere).
 */
object Scanner {
    private val LADDER = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1500.0, 4000.0, 10000.0, 25000.0, 50000.0)
    private const val MIN_POOL_USD = 20.0
    private const val CHAINLINK_DIVERGENCE_BPS = 100.0
    const val MAX_PLAUSIBLE_GAP_BPS = 5_000.0
    const val NO_ROUTE_TTL_MS = 6 * 3_600_000L
    private const val TOKENS_TTL_MS = 3_600_000L
    private const val NO_POOLS_TTL_MS = 6 * 3_600_000L
    private const val YAHOO_THREADS = 8
    private const val KYBER_THREADS = 6

    /** Feed Chainlink "Base Tokenized Equities" (prezzo per token Coinbase). */
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
        val noPools: MutableMap<String, Long> = ConcurrentHashMap(),   // token senza pool: ricontrollo ogni 6 ore
    )

    private class Candidate(
        val token: CbToken, val fair: Double, val gap: Double, val base: Opportunity,
        val poolLiq: Double, val totalLiq: Double, val halted: Boolean,
    )

    private val running = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    fun yahooSymbol(ticker: String) = ticker.replace('.', '-').replace('/', '-')

    private fun px(v: Double) = String.format(Locale.ITALY, "%.2f", v)
    private fun usd(v: Double) = String.format(Locale.ITALY, "$%,.2f", v)
    private fun usd0(v: Double) = String.format(Locale.ITALY, "$%,.0f", v)

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
        st.noPools.entries.removeAll { it.value < started }
        val opps = ConcurrentHashMap<String, Opportunity>()
        var total = 0

        fun outcome(inProgress: Boolean, progress: String) = ScanOutcome(
            started, session, total, sortOpps(opps.values), synchronized(notes) { notes.toList() },
            inProgress, progress, ((System.currentTimeMillis() - started) / 1000).toInt(),
            synchronized(feed) { feed.joinToString(" · ") },
        )

        // ---- elenco token delle chain attive (in cache per un'ora)
        onUpdate(outcome(true, "Elenco token…"))
        val posChains = positions.map { it.chain }.toSet()
        val chains = (s.chains() + posChains).distinct()
        val allTokens = cachedTokens(st, started, chains, notes)
        val enabled = s.chains().toSet()
        total = allTokens.count { it.chain in enabled }
        val byAddr = allTokens.associateBy { it.address.lowercase() }
        val wanted = s.tickerSet()
        val excluded = s.excludeSet()
        val screenAddr = allTokens.filter {
            it.chain in enabled && (wanted.isEmpty() || it.ticker.uppercase() in wanted) &&
                it.ticker.uppercase() !in excluded
        }.map { it.address.lowercase() }.toSet()
        val posAddr = positions.map { it.address.lowercase() }.toSet()
        val scope = allTokens.filter { val a = it.address.lowercase(); a in screenAddr || a in posAddr }

        // ---- prezzi di Borsa Robinhood (una richiesta, in parallelo a DexScreener)
        val refExec = Executors.newSingleThreadExecutor()
        val rhFuture = refExec.submit<Pair<Map<String, RefQuote>, Set<String>>> { Api.robinhoodQuotes() }
        refExec.shutdown()

        // ---- 1. DexScreener: pool principale di ogni token
        onUpdate(outcome(true, "Prezzi delle pool (DexScreener)…"))
        val t0 = System.currentTimeMillis()
        val dsPools = mutableMapOf<String, List<PoolPrice>>()
        var dsFailed = 0
        for (chain in chains) {
            val (m, f) = Api.dexscreener(chain, scope.filter { it.chain == chain }.map { it.address })
            dsPools.putAll(m)
            dsFailed += f
        }
        feed += "DexScreener ${System.currentTimeMillis() - t0} ms"
        if (dsFailed > 0) notes += "DexScreener: $dsFailed richieste fallite"

        // ---- 2. GeckoTerminal parte subito dopo, in background: tutti i token tranne quelli
        //         che all'ultimo controllo non avevano nessuna pool (ricontrollati ogni 6 ore)
        val gtStart = System.currentTimeMillis()
        val gtScope = scope.filter { val a = it.address.lowercase(); dsPools.containsKey(a) || a !in st.noPools }
        val gtExec = Executors.newSingleThreadExecutor()
        val gtFuture = gtExec.submit<Pair<Map<String, List<PoolPrice>>, Int>> {
            val all = mutableMapOf<String, List<PoolPrice>>()
            var failed = 0
            for (chain in chains) {
                val r = Api.gecko(chain, gtScope.filter { it.chain == chain }.map { it.address })
                all.putAll(r.pools)
                failed += r.failed
                // interrogati senza alcuna pool, né qui né su DexScreener: si salteranno per 6 ore
                for (a in r.queried) {
                    if (!r.pools.containsKey(a) && !dsPools.containsKey(a)) st.noPools[a] = started + NO_POOLS_TTL_MS
                    else st.noPools.remove(a)
                }
            }
            Pair(all, failed)
        }
        gtExec.shutdown()

        val (rhQuotes, halted) = try {
            rhFuture.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            notes += "Prezzi Robinhood non disponibili: uso Yahoo"
            Pair(emptyMap<String, RefQuote>(), emptySet<String>())
        }
        if (rhQuotes.isNotEmpty()) feed += "Borsa: Robinhood ${rhQuotes.size} titoli"
        val ws = ConcurrentHashMap<String, RefQuote>(rhQuotes)
        val cl = try {
            Api.chainlink(FEEDS.filterKeys { k -> scope.any { it.chain == Chain.BASE && it.ticker == k } })
        } catch (e: Exception) {
            emptyMap()
        }

        val poolsBy = ConcurrentHashMap<String, List<PoolPrice>>()
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
                val gtNote = if (gtFuture.isDone) "" else " · pool piccole da GeckoTerminal in arrivo (fino a 1–2 min)…"
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

        /** Prezzi Yahoo solo per i titoli che Robinhood non copre. */
        fun fillReference(tokens: List<CbToken>) {
            val missing = tokens.filter { !ws.containsKey(it.ticker) }
            if (missing.isNotEmpty()) ws.putAll(fetchYahoo(missing))
        }

        /** Confronto con Wall Street: sconto sulla pool più economica, rincaro sulla più cara. */
        fun screen(tokens: List<CbToken>, pools: Map<String, List<PoolPrice>>) {
            val found = mutableListOf<Candidate>()
            for (t in tokens) {
                val a = t.address.lowercase()
                val list = pools[a]?.filter {
                    it.priceUsd > 0 && it.priceUsd.isFinite() && (it.liquidityUsd >= MIN_POOL_USD || a in posAddr)
                } ?: continue
                if (list.isEmpty()) continue
                poolsBy[a] = list
                val w = ws[t.ticker]
                val c = if (t.chain == Chain.BASE) cl[t.ticker] else null
                val fair = pickFair(t, w, c, started, s.maxRefAgeMin)
                if (fair == null || !(fair.first > 0) || !fair.first.isFinite()) {
                    noRef += t.label
                    continue
                }
                noRef -= t.label
                val (fairPx, src, age) = fair
                fairBy[a] = fairPx
                if (a !in screenAddr) continue
                if (candidates.contains(a)) continue   // già in verifica on-chain: decide la verifica
                val cheap = list.minBy { it.priceUsd }
                val rich = list.maxBy { it.priceUsd }
                val buyGap = (cheap.priceUsd - fairPx) / fairPx * 1e4
                val sellGap = (rich.priceUsd - fairPx) / fairPx * 1e4
                val useBuy = s.buyOnly || -buyGap >= sellGap
                val pool = if (useBuy) cheap else rich
                val gap = if (useBuy) buyGap else sellGap
                val isHalted = t.ticker in halted
                val extra = mutableListOf<String>()
                if (isHalted) extra += "titolo sospeso in Borsa"
                if (list.size > 1) extra += "${list.size} pool: prezzi da ${px(cheap.priceUsd)} a ${px(rich.priceUsd)}"
                val base = Opportunity(
                    ticker = t.ticker, symbol = t.symbol, address = t.address, session = session,
                    fairPrice = fairPx, refSource = src, refAgeMin = age,
                    dexBuy = null, dexSell = null, poolPrice = pool.priceUsd, liquidityUsd = pool.liquidityUsd,
                    dex = pool.dex, pair = pool.pair, gapBps = gap, chainlink = c?.price,
                    best = null, fixed = null, actionable = false, verified = false,
                    notes = baseNotes(t, session, tradable, age, s, w, c, started) + extra,
                    chain = t.chain,
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
                    found += Candidate(t, fairPx, gap, base, pool.liquidityUsd, list.sumOf { it.liquidityUsd }, isHalted)
                }
            }
            // i token meno liquidi per primi: è lì che i prezzi si scostano di più
            found.sortedBy { it.poolLiq }.forEach { verify(it) }
        }

        // ---- quadro con DexScreener: subito a schermo
        val dsTokens = scope.filter { dsPools.containsKey(it.address.lowercase()) }
        onUpdate(outcome(true, "Prezzi Wall Street…"))
        fillReference(dsTokens)
        screen(dsTokens, dsPools)
        emitProgress(force = true)

        // ---- posizioni: controllate subito, senza aspettare GeckoTerminal
        if (positions.isNotEmpty()) {
            val needRef = positions.mapNotNull { byAddr[it.address.lowercase()] }
            fillReference(needRef)
            onPositions(positions.map { p ->
                val a = p.address.lowercase()
                val t = byAddr[a]
                val fair = fairBy[a] ?: t?.let { tok ->
                    pickFair(tok, ws[tok.ticker], if (tok.chain == Chain.BASE) cl[tok.ticker] else null,
                        started, s.maxRefAgeMin)?.first
                }
                checkPosition(p, t, fair, poolsBy[a] ?: dsPools[a], session, s, started)
            })
            emitProgress(force = true)
        }

        // ---- GeckoTerminal: tutte le pool, comprese le più piccole
        val gt = try {
            gtFuture.get(150, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
        if (gt == null) {
            notes += "GeckoTerminal non disponibile in questa scansione: alcune pool potrebbero mancare"
        } else {
            val (gtPools, gtFailed) = gt
            feed += "GeckoTerminal " + String.format(Locale.ITALY, "%.1f s", (System.currentTimeMillis() - gtStart) / 1000.0) +
                " (${gtScope.size} token)"
            if (gtFailed > 0) notes += "GeckoTerminal: $gtFailed richieste fallite"
            // unione delle pool delle due fonti; si riesaminano i token con pool nuove
            val merged = mutableMapOf<String, List<PoolPrice>>()
            val changed = mutableListOf<CbToken>()
            for (t in scope) {
                val a = t.address.lowercase()
                val g = gtPools[a] ?: continue
                val d = dsPools[a] ?: emptyList()
                val known = d.mapNotNull { it.pair?.lowercase() }.toSet()
                val added = g.filter { it.pair == null || it.pair.lowercase() !in known }
                if (added.isEmpty()) continue
                merged[a] = d + added
                changed += t
            }
            if (changed.isNotEmpty()) {
                fillReference(changed)
                screen(changed, merged)
            }
            emitProgress(force = true)
        }

        // ---- fine della verifica on-chain
        verifyExec.shutdown()
        verifyExec.awaitTermination(8, TimeUnit.MINUTES)

        val skipped = screenAddr.count { !poolsBy.containsKey(it) }
        val missingRef = synchronized(noRef) { noRef.sorted() }
        if (missingRef.isNotEmpty()) notes += "Senza prezzo di Borsa: ${missingRef.joinToString(", ")}"
        if (skipped > 0) notes += "Senza pool con liquidità: $skipped token (saltati)"
        return outcome(false, "")
    }

    /** Quota eseguibile, margine, size ottimale e profitto netto di un candidato. */
    private fun sizeCandidate(c: Candidate, s: Settings, session: String, tradable: Set<String>,
                              st: State, now: Long): Opportunity {
        val t = c.token
        val chain = t.chain
        val fair = c.fair
        val costs = Sizing.Costs(
            s.feeBps, s.feePerShare,
            if (session == "regular") s.halfSpreadRegular else s.halfSpreadExtended,
            s.fixedCost, t.multiplier,
        )
        val q = Sizing.QuoteFn { dir, amt ->
            if (dir == Sizing.BUY) Api.quote(chain, chain.stable, chain.stableDecimals, t.address, t.decimals, amt)
            else Api.quote(chain, t.address, t.decimals, chain.stable, chain.stableDecimals, amt)
        }
        // prova proporzionata alla pool: in una pool da $300 anche $100 sposterebbero il prezzo
        val probeUsd = (c.poolLiq * 0.005).coerceIn(5.0, 100.0)
        val dir = if (c.gap < 0) Sizing.BUY else Sizing.SELL
        val px: Double = try {
            if (dir == Sizing.BUY) {
                probeUsd / q.quote(Sizing.BUY, probeUsd).amountOut
            } else {
                val tk = probeUsd / fair
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
                notes = c.base.notes + "il prezzo eseguibile (${px(px)}) non copre i costi della copertura")
        }
        // scala di size adattata alla liquidità: mai oltre metà della liquidità totale del token
        val cap = max(50.0, c.totalLiq * 0.5)
        val maxN = min(s.maxNotional, cap)
        val ladder = LADDER.filter { it <= maxN }
        val fixedSize = s.tradeSize.takeIf { it <= maxN }
        val (best, fixed) = Sizing.search(dir, fair, q, costs, ladder, maxN, fixedSize)
        val actionable = best != null && !c.halted && session in tradable && c.base.refAgeMin <= s.maxRefAgeMin &&
            t.pausedFeatures.isEmpty() && best.netUsd >= s.minProfit && best.netBps >= s.minBps
        val why = when {
            best == null -> "quote non disponibili per le size provate (riprova alla prossima scansione)"
            best.netUsd <= 0 -> "netto negativo a ogni size: il migliore è ${usd(best.netUsd)} con ${usd0(best.notionalUsd)} " +
                "(lordo ${usd(best.grossUsd)}, costi ${usd(best.costsUsd)} tra gas e copertura)"
            else -> null
        }
        return c.base.copy(dexBuy = buyPx, dexSell = sellPx, best = best, fixed = fixed,
            actionable = actionable, verified = true, notes = c.base.notes + listOfNotNull(why))
    }

    /**
     * Quota della posizione vendibile chiudendo il gap: prezzo medio di vendita eseguibile
     * ≥ prezzo di Borsa × (1 − tolleranza). Si cerca la quota più alta a passi del 10%.
     */
    private fun checkPosition(p: Position, t: CbToken?, fair: Double?, pools: List<PoolPrice>?,
                              session: String, s: Settings, now: Long): PositionStatus {
        val spot = pools?.maxOfOrNull { it.priceUsd }   // la pool che paga di più
        fun status(fraction: Double = 0.0, sellTokens: Double? = null, proceeds: Double? = null,
                   avg: Double? = null, pnl: Double? = null, note: String? = null) =
            PositionStatus(p.id, now, fair, spot, fraction, sellTokens, proceeds, avg, pnl, note)

        if (t == null) return status(note = "token non più presente nell'elenco dell'emittente")
        if (fair == null) {
            return status(note = if (pools == null) "prezzo on-chain non disponibile in questa scansione"
            else "prezzo di Borsa non disponibile")
        }
        if (p.tokens <= 0) return status(note = "posizione vuota")
        val target = fair * (1 - s.closeTolBps / 1e4)
        val closed = if (session != "regular") "Borsa ${Market.label(session)}: confronto con l'ultimo prezzo" else null
        val chain = t.chain
        var lo = 0
        var hi = 10
        var bestK = 0
        var bestQ: DexQuote? = null
        try {
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val amount = p.tokens * mid / 10.0
                val qd = Api.quote(chain, t.address, t.decimals, chain.stable, chain.stableDecimals, amount)
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
            val gap = spot?.let { " (pool ${((it - fair) / fair * 1e4).toInt()} bps)" } ?: ""
            return status(note = listOfNotNull("gap ancora aperto: la vendita del 10% non arriva al prezzo di Borsa$gap", closed)
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
            if (abs(diff) > CHAINLINK_DIVERGENCE_BPS) notes += "Borsa e Chainlink divergono di ${diff.toInt()} bps"
        }
        return notes
    }

    /** Prima i segnali sopra soglia, poi il rendimento netto %, poi lo sconto. */
    private fun sortOpps(c: Collection<Opportunity>): List<Opportunity> = c.sortedWith(
        compareByDescending<Opportunity> { it.actionable }
            .thenByDescending { o -> o.best?.takeIf { it.netUsd > 0 }?.netBps ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { abs(it.gapBps) }
    )

    /** Elenchi token di Coinbase e Robinhood, in cache per un'ora (se un'API non risponde usa la cache). */
    private fun cachedTokens(st: State, now: Long, chains: List<Chain>, notes: MutableList<String>): List<CbToken> {
        val cached = st.tokensJson?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { CbToken.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
        val fresh = now - st.tokensAt < TOKENS_TTL_MS
        val out = mutableListOf<CbToken>()
        var refreshed = false
        for (chain in chains) {
            val have = cached.filter { it.chain == chain }
            if (fresh && have.isNotEmpty()) {
                out += have
                continue
            }
            val got = try {
                if (chain == Chain.BASE) Api.listCoinbaseTokens() else Api.listRobinhoodTokens()
            } catch (e: Exception) {
                notes += "Elenco token ${chain.issuer} non disponibile${if (have.isNotEmpty()) ": uso quello salvato" else ""}"
                null
            }
            if (got != null && got.isNotEmpty()) {
                out += got
                refreshed = true
            } else {
                out += have
            }
        }
        if (refreshed) {
            st.tokensJson = JSONArray().apply { out.forEach { put(it.toJson()) } }.toString()
            st.tokensAt = now
        }
        if (out.isEmpty()) throw RuntimeException("Elenco token non disponibile")
        return out
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

    /** -> (prezzo per token, fonte, età in minuti). Priorità a Borsa fresca, poi Chainlink. */
    private fun pickFair(t: CbToken, w: RefQuote?, c: RefQuote?, now: Long, maxAge: Double): Triple<Double, String, Double>? {
        fun age(r: RefQuote) = max(0.0, (now - r.asOfMillis) / 60000.0)
        return when {
            w != null && age(w) <= maxAge -> Triple(w.price * t.multiplier, w.source, age(w))
            c != null && age(c) <= maxAge -> Triple(c.price, c.source, age(c))
            w != null -> Triple(w.price * t.multiplier, w.source, age(w))
            c != null -> Triple(c.price, c.source, age(c))
            else -> null
        }
    }
}

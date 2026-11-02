package com.wkisai.tokenarb

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpError(val status: Int, val body: String) : RuntimeException("HTTP $status: ${body.take(160)}")

/** L'aggregatore non trova liquidità per il token (KyberSwap 4008/4010/4011). */
class NoRouteException(msg: String) : RuntimeException(msg)

/** Chiamate HTTP alle fonti dati: Coinbase, Robinhood, Yahoo, Chainlink, DexScreener, GeckoTerminal, KyberSwap. */
object Api {
    private val NO_ROUTE_CODES = setOf(4008, 4010, 4011)
    private const val UA = "Mozilla/5.0 (Android) TokenArb/1.0"
    private const val BASE_RPC = "https://mainnet.base.org"
    private const val ROBINHOOD_CHAIN_ID = 4663

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun num(s: String?): Double? = s?.toDoubleOrNull()?.takeIf { it.isFinite() }

    // ---------------------------------------------------------------- elenchi token
    /** Tutti gli stock token Coinbase su Base (API ufficiale, paginata). */
    fun listCoinbaseTokens(): List<CbToken> {
        val out = mutableListOf<CbToken>()
        var pageToken: String? = null
        repeat(50) {
            var url = "https://api.coinbase.com/v1/tokenized-stocks?page_size=500"
            if (pageToken != null) url += "&page_token=" + enc(pageToken!!)
            val o = JSONObject(request(url))
            val arr = o.optJSONArray("tokens") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val sym = t.optString("symbol")
                val paused = t.optJSONArray("paused_features")
                out += CbToken(
                    address = t.getString("contract_address"),
                    symbol = sym,
                    ticker = if (sym.endsWith("c")) sym.dropLast(1) else sym,
                    name = t.optString("name", sym),
                    decimals = t.optString("decimals", "18").toIntOrNull() ?: 18,
                    multiplier = num(t.optString("multiplier", "1"))?.takeIf { it > 0 } ?: 1.0,
                    pausedFeatures = if (paused == null) emptyList()
                    else (0 until paused.length()).map { paused.optInt(it) },
                    chain = Chain.BASE,
                )
            }
            pageToken = o.optString("next_page_token", "").ifEmpty { null }
            if (pageToken == null) return out
        }
        return out
    }

    /** Stock token Robinhood attivi su Robinhood Chain (API ufficiale /rhj/assets, 18 decimali). */
    fun listRobinhoodTokens(): List<CbToken> {
        val o = JSONObject(request("https://api.robinhood.com/rhj/assets"))
        val arr = o.optJSONArray("assets") ?: JSONArray()
        val out = mutableListOf<CbToken>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            if (a.optString("status") != "ASSET_STATUS_ACTIVE") continue
            val sym = a.optString("tokenSymbol")
            if (sym.isEmpty()) continue
            val deps = a.optJSONArray("deployments") ?: JSONArray()
            var address: String? = null
            for (j in 0 until deps.length()) {
                val d = deps.getJSONObject(j)
                if (d.optInt("chainId") == ROBINHOOD_CHAIN_ID) address = d.optString("contractAddress")
            }
            if (address.isNullOrEmpty()) continue
            out += CbToken(
                address = address,
                symbol = sym,
                ticker = sym,
                name = a.optString("tokenName", sym),
                decimals = 18,
                multiplier = num(a.optString("currentMultiplier"))?.takeIf { it > 0 } ?: 1.0,
                chain = Chain.ROBINHOOD,
            )
        }
        return out
    }

    // ---------------------------------------------------------------- prezzi di Borsa
    /**
     * Bid/ask del titolo sottostante per tutti gli stock Robinhood in una sola richiesta (/rhj/prices).
     * Prezzo per azione (non moltiplicato). -> (quotazioni per ticker, ticker sospesi)
     */
    fun robinhoodQuotes(): Pair<Map<String, RefQuote>, Set<String>> {
        val o = JSONObject(request("https://api.robinhood.com/rhj/prices"))
        val arr = o.optJSONArray("quotes") ?: JSONArray()
        val out = mutableMapOf<String, RefQuote>()
        val halted = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val q = arr.getJSONObject(i)
            val sym = q.optString("tokenSymbol")
            val bid = num(q.optString("bid")) ?: continue
            val ask = num(q.optString("ask")) ?: continue
            if (sym.isEmpty() || bid <= 0 || ask <= 0) continue
            val ts = try { Instant.parse(q.optString("generatedAt")).toEpochMilli() } catch (e: Exception) { now }
            out[sym] = RefQuote(sym, (bid + ask) / 2, ts, "robinhood")
            if (q.optBoolean("isTradingHalt")) halted += sym
        }
        return Pair(out, halted)
    }

    /** Ultimo prezzo a 1 minuto da Yahoo, pre/after-market inclusi (riserva per i titoli non coperti). */
    fun yahoo(ticker: String, yahooSymbol: String): RefQuote? {
        for (range in listOf("1d", "5d")) {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/${enc(yahooSymbol)}" +
                "?interval=1m&range=$range&includePrePost=true"
            val res = JSONObject(request(url)).getJSONObject("chart")
                .optJSONArray("result")?.optJSONObject(0) ?: continue
            val ts = res.optJSONArray("timestamp")
            val closes = res.optJSONObject("indicators")?.optJSONArray("quote")
                ?.optJSONObject(0)?.optJSONArray("close")
            if (ts != null && closes != null) {
                for (i in minOf(ts.length(), closes.length()) - 1 downTo 0) {
                    if (!closes.isNull(i)) {
                        val px = closes.getDouble(i)
                        if (px > 0 && px.isFinite()) return RefQuote(ticker, px, ts.getLong(i) * 1000, "yahoo")
                    }
                }
            }
            val meta = res.optJSONObject("meta")
            if (meta != null && meta.has("regularMarketPrice")) {
                val px = meta.getDouble("regularMarketPrice")
                if (px > 0 && px.isFinite()) {
                    return RefQuote(ticker, px, meta.optLong("regularMarketTime") * 1000, "yahoo")
                }
            }
        }
        return null
    }

    /** latestRoundData() sui feed Chainlink "Base Tokenized Equities": prezzo per token, 8 decimali. */
    fun chainlink(feeds: Map<String, String>): Map<String, RefQuote> {
        if (feeds.isEmpty()) return emptyMap()
        val keys = feeds.keys.toList()
        val batch = JSONArray()
        keys.forEachIndexed { i, k ->
            batch.put(JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", i); put("method", "eth_call")
                put("params", JSONArray()
                    .put(JSONObject().put("to", feeds[k]).put("data", "0xfeaf968c"))
                    .put("latest"))
            })
        }
        val resp = JSONArray(request(BASE_RPC, batch.toString()))
        val out = mutableMapOf<String, RefQuote>()
        for (i in 0 until resp.length()) {
            val r = resp.getJSONObject(i)
            val hex = r.optString("result", "")
            if (hex.length < 2 + 64 * 5) continue
            val words = (0 until 5).map { hex.substring(2 + 64 * it, 2 + 64 * (it + 1)) }
            var answer = BigInteger(words[1], 16)
            if (answer.testBit(255)) answer = answer.subtract(BigInteger.ONE.shiftLeft(256))
            val updated = BigInteger(words[3], 16).toLong() * 1000
            val price = answer.toDouble() / 1e8
            if (price <= 0) continue
            val t = keys[r.getInt("id")]
            out[t] = RefQuote(t, price, updated, "chainlink", perToken = true)
        }
        return out
    }

    // ---------------------------------------------------------------- DexScreener
    private fun addPool(out: ConcurrentHashMap<String, List<PoolPrice>>, token: String, p: PoolPrice) {
        out.compute(token) { _, v -> (v ?: emptyList()) + p }
    }

    /**
     * Pool principale di ogni token da DexScreener (una per token), con qualsiasi controvaluta
     * (USDC, USDG, WETH, …): il prezzo è già in USD. Richieste parallele da 30 indirizzi.
     * -> (pool per indirizzo in minuscolo, richieste fallite)
     */
    fun dexscreener(chain: Chain, addresses: List<String>): Pair<Map<String, List<PoolPrice>>, Int> {
        val out = ConcurrentHashMap<String, List<PoolPrice>>()
        val failed = AtomicInteger(0)
        val chunks = addresses.chunked(30)
        if (chunks.isEmpty()) return Pair(out, 0)
        val pool = Executors.newFixedThreadPool(minOf(8, chunks.size))
        try {
            for (chunk in chunks) {
                pool.execute {
                    try {
                        val url = "https://api.dexscreener.com/tokens/v1/${chain.dexscreener}/" + chunk.joinToString(",")
                        val text = try {
                            request(url)
                        } catch (e: HttpError) {
                            if (e.status != 429) throw e
                            Thread.sleep(1_000)
                            request(url)
                        }
                        parseDexScreener(chain, JSONArray(text), chunk.map { it.lowercase() }.toSet(), out)
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        }
        return Pair(out, failed.get())
    }

    private fun parseDexScreener(chain: Chain, arr: JSONArray, wanted: Set<String>,
                                 out: ConcurrentHashMap<String, List<PoolPrice>>) {
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("chainId") != chain.dexscreener) continue
            val baseTok = p.optJSONObject("baseToken") ?: continue
            val quoteTok = p.optJSONObject("quoteToken") ?: continue
            val base = baseTok.optString("address").lowercase()
            val quote = quoteTok.optString("address").lowercase()
            val tokenIsBase = base in wanted
            if (!tokenIsBase && quote !in wanted) continue
            val token = if (tokenIsBase) base else quote
            val otherSym = if (tokenIsBase) quoteTok.optString("symbol") else baseTok.optString("symbol")
            val usd = num(p.optString("priceUsd")) ?: continue
            val native = num(p.optString("priceNative")) ?: continue
            // priceUsd e priceNative si riferiscono al token base della pool
            val px = if (tokenIsBase) usd else if (native > 0) usd / native else 0.0
            if (!(px > 0) || !px.isFinite()) continue
            val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0)?.takeIf { it.isFinite() } ?: 0.0
            val labels = p.optJSONArray("labels")
            val labelText = if (labels == null) "" else (0 until labels.length()).joinToString(" ") { labels.optString(it) }
            val dex = "${p.optString("dexId", "?")} $labelText".trim() + if (otherSym.isNotEmpty()) " · $otherSym" else ""
            addPool(out, token, PoolPrice(px, liq, dex, p.optString("pairAddress").ifEmpty { null }))
        }
    }

    // ---------------------------------------------------------------- GeckoTerminal
    /** Pausa tra le richieste: GeckoTerminal rifiuta (HTTP 429) le richieste ravvicinate o parallele. */
    private const val GECKO_GAP_MS = 1_200L

    /**
     * Tutte le pool principali di ogni token da GeckoTerminal, ognuna con il proprio prezzo:
     * trova le pool piccole che DexScreener non riporta. Richieste in sequenza, a blocchi di 30,
     * con pausa e attese crescenti se il servizio limita la frequenza. -> (pool per indirizzo, richieste fallite)
     */
    fun gecko(chain: Chain, addresses: List<String>): Pair<Map<String, List<PoolPrice>>, Int> {
        val out = ConcurrentHashMap<String, List<PoolPrice>>()
        var failed = 0
        val headers = mapOf("Accept" to "application/json;version=20230302")
        for ((i, chunk) in addresses.chunked(30).withIndex()) {
            if (i > 0) Thread.sleep(GECKO_GAP_MS)
            val url = "https://api.geckoterminal.com/api/v2/networks/${chain.gecko}/tokens/multi/" +
                chunk.joinToString(",") + "?include=top_pools"
            var body: String? = null
            for (attempt in 0..3) {
                try {
                    body = request(url, headers = headers)
                    break
                } catch (e: HttpError) {
                    if (e.status != 429 || attempt == 3) break
                    Thread.sleep(2_000L shl attempt)   // 2, 4, 8 s
                } catch (e: Exception) {
                    break
                }
            }
            val text = body
            if (text == null) {
                failed++
                continue
            }
            try {
                parseGecko(JSONObject(text), out)
            } catch (e: Exception) {
                failed++
            }
        }
        return Pair(out, failed)
    }

    private fun parseGecko(o: JSONObject, out: ConcurrentHashMap<String, List<PoolPrice>>) {
        val pools = mutableMapOf<String, JSONObject>()
        val inc = o.optJSONArray("included") ?: JSONArray()
        for (i in 0 until inc.length()) {
            val p = inc.getJSONObject(i)
            if (p.optString("type") == "pool") pools[p.optString("id")] = p
        }
        val data = o.optJSONArray("data") ?: JSONArray()
        for (i in 0 until data.length()) {
            val t = data.getJSONObject(i)
            val a = t.optJSONObject("attributes") ?: continue
            val addr = a.optString("address").lowercase()
            if (addr.isEmpty()) continue
            val refs = t.optJSONObject("relationships")?.optJSONObject("top_pools")
                ?.optJSONArray("data") ?: JSONArray()
            for (j in 0 until refs.length()) {
                val p = pools[refs.getJSONObject(j).optString("id")] ?: continue
                val pa = p.optJSONObject("attributes") ?: continue
                val rel = p.optJSONObject("relationships")
                val baseId = rel?.optJSONObject("base_token")?.optJSONObject("data")?.optString("id")?.lowercase() ?: ""
                val px = if (baseId.endsWith(addr)) num(pa.optString("base_token_price_usd"))
                else num(pa.optString("quote_token_price_usd"))
                if (px == null || !(px > 0)) continue
                val liq = num(pa.optString("reserve_in_usd")) ?: 0.0
                val dexId = rel?.optJSONObject("dex")?.optJSONObject("data")?.optString("id") ?: "?"
                val name = pa.optString("name")
                val label = if (name.isNotEmpty()) "$dexId · $name" else dexId
                addPool(out, addr, PoolPrice(px, liq, label, pa.optString("address").ifEmpty { null }))
            }
        }
    }

    // ---------------------------------------------------------------- KyberSwap
    /** Massimo ~8 richieste al secondo verso KyberSwap, anche da più thread. */
    private const val KYBER_MIN_INTERVAL_MS = 125L
    private val kyberLock = Any()
    private var kyberNext = 0L

    private fun kyberSlot() {
        val wait = synchronized(kyberLock) {
            val now = System.currentTimeMillis()
            val start = maxOf(now, kyberNext)
            kyberNext = start + KYBER_MIN_INTERVAL_MS
            start - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    /** Quota eseguibile on-chain (tutte le pool della chain): fee, slippage e gas inclusi. */
    fun quote(chain: Chain, tokenIn: String, decIn: Int, tokenOut: String, decOut: Int, amountIn: Double): DexQuote {
        val raw = BigDecimal.valueOf(amountIn).movePointRight(decIn).toBigInteger()
        if (raw.signum() <= 0) throw RuntimeException("importo troppo piccolo")
        val url = "https://aggregator-api.kyberswap.com/${chain.kyber}/api/v1/routes" +
            "?tokenIn=$tokenIn&tokenOut=$tokenOut&amountIn=$raw"
        for (attempt in 0..2) {
            kyberSlot()
            val text = try {
                request(url, headers = mapOf("X-Client-Id" to "tokenarb-android"))
            } catch (e: HttpError) {
                val err = try { JSONObject(e.body) } catch (x: Exception) { null }
                val code = err?.optInt("code", -1) ?: -1
                if (code in NO_ROUTE_CODES) throw NoRouteException("KyberSwap $code: ${err?.optString("message")}")
                if ((e.status == 429 || e.status >= 500) && attempt < 2) {
                    Thread.sleep(1_000L * (attempt + 1))
                    continue
                }
                throw RuntimeException("KyberSwap HTTP ${e.status}: ${err?.optString("message") ?: e.body.take(120)}")
            }
            val body = JSONObject(text)
            if (body.optInt("code", 0) != 0 || !body.has("data")) {
                val code = body.optInt("code", -1)
                if (code in NO_ROUTE_CODES) throw NoRouteException("KyberSwap $code")
                throw RuntimeException("KyberSwap: ${body.optString("message")}")
            }
            val rs = body.getJSONObject("data").getJSONObject("routeSummary")
            val out = BigDecimal(rs.getString("amountOut")).movePointLeft(decOut).toDouble()
            val gas = num(rs.optString("gasUsd", "0")) ?: 0.0
            val l1 = num(rs.optString("l1FeeUsd", "0")) ?: 0.0
            return DexQuote(amountIn, out, gas + l1)
        }
        throw RuntimeException("KyberSwap: troppi tentativi")
    }
}

package com.wkisai.tokenarb

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpError(val status: Int, val body: String) : RuntimeException("HTTP $status: ${body.take(160)}")

/** L'aggregatore non trova liquidità per il token (KyberSwap 4008/4010/4011). */
class NoRouteException(msg: String) : RuntimeException(msg)

/** Chiamate HTTP alle fonti dati (Coinbase, Yahoo, Chainlink via RPC, KyberSwap). */
object Api {
    private val NO_ROUTE_CODES = setOf(4008, 4010, 4011)
    private const val UA = "Mozilla/5.0 (Android) TokenArb/1.0"
    const val USDC = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    const val USDC_DECIMALS = 6
    private const val BASE_RPC = "https://mainnet.base.org"
    private const val KYBER = "https://aggregator-api.kyberswap.com/base/api/v1/routes"

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

    // ---------------------------------------------------------------- Coinbase
    /** Tutti i token azionari Coinbase su Base (API ufficiale, paginata). */
    fun listTokens(): List<CbToken> {
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
                    multiplier = t.optString("multiplier", "1").toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
                    pausedFeatures = if (paused == null) emptyList()
                    else (0 until paused.length()).map { paused.optInt(it) },
                )
            }
            pageToken = o.optString("next_page_token", "").ifEmpty { null }
            if (pageToken == null) return out
        }
        return out
    }

    // ---------------------------------------------------------------- Yahoo
    /** Ultimo prezzo a 1 minuto, pre/after-market inclusi. Prezzo per azione. */
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
                        return RefQuote(ticker, closes.getDouble(i), ts.getLong(i) * 1000, "yahoo")
                    }
                }
            }
            val meta = res.optJSONObject("meta")
            if (meta != null && meta.has("regularMarketPrice")) {
                return RefQuote(ticker, meta.getDouble("regularMarketPrice"),
                    meta.optLong("regularMarketTime") * 1000, "yahoo")
            }
        }
        return null
    }

    // ---------------------------------------------------------------- Chainlink
    /** latestRoundData() sui feed "Base Tokenized Equities": prezzo per token, 8 decimali. */
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
            val t = keys[r.getInt("id")]
            out[t] = RefQuote(t, answer.toDouble() / 1e8, updated, "chainlink", perToken = true)
        }
        return out
    }

    // ---------------------------------------------------------------- DexScreener
    private const val DEXSCREENER = "https://api.dexscreener.com/tokens/v1/base/"
    private val USDC_LC = USDC.lowercase()

    private fun isAero(p: PoolPrice) = p.dex.lowercase().contains("aero")

    /** true se la pool [x] è preferibile a [y]: prima Aero, poi la più liquida. */
    private fun better(x: PoolPrice, y: PoolPrice): Boolean =
        if (isAero(x) != isAero(y)) isAero(x) else x.liquidityUsd > y.liquidityUsd

    /**
     * Prezzo di ogni token nella sua pool token/USDC (Aero preferita) da DexScreener.
     * Richieste parallele da 30 indirizzi: ~0,2 s per tutti i token. -> (prezzi, richieste fallite)
     */
    fun dexscreener(addresses: List<String>): Pair<Map<String, PoolPrice>, Int> {
        val out = ConcurrentHashMap<String, PoolPrice>()
        val failed = AtomicInteger(0)
        val chunks = addresses.chunked(30)
        if (chunks.isEmpty()) return Pair(out, 0)
        val pool = Executors.newFixedThreadPool(minOf(8, chunks.size))
        try {
            for (chunk in chunks) {
                pool.execute {
                    try {
                        val url = DEXSCREENER + chunk.joinToString(",")
                        val text = try {
                            request(url)
                        } catch (e: HttpError) {
                            if (e.status != 429) throw e
                            Thread.sleep(1_000)
                            request(url)
                        }
                        parseDexScreener(JSONArray(text), chunk.map { it.lowercase() }.toSet(), out)
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

    private fun parseDexScreener(arr: JSONArray, wanted: Set<String>, out: ConcurrentHashMap<String, PoolPrice>) {
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (p.optString("chainId") != "base") continue
            val base = p.optJSONObject("baseToken")?.optString("address")?.lowercase() ?: continue
            val quote = p.optJSONObject("quoteToken")?.optString("address")?.lowercase() ?: continue
            val tokenIsBase = base in wanted
            if (!tokenIsBase && quote !in wanted) continue
            val token = if (tokenIsBase) base else quote
            val other = if (tokenIsBase) quote else base
            if (other != USDC_LC) continue
            val usd = p.optString("priceUsd").toDoubleOrNull() ?: continue
            val native = p.optString("priceNative").toDoubleOrNull() ?: continue
            // priceUsd e priceNative si riferiscono al token base della pool
            val px = if (tokenIsBase) usd else if (native > 0) usd / native else 0.0
            if (!(px > 0) || !px.isFinite()) continue
            val liq = p.optJSONObject("liquidity")?.optDouble("usd", 0.0)?.takeIf { it.isFinite() } ?: 0.0
            val labels = p.optJSONArray("labels")
            val labelText = if (labels == null) "" else (0 until labels.length()).joinToString(" ") { labels.optString(it) }
            val dex = (p.optString("dexId", "?") + " " + labelText).trim()
            val cand = PoolPrice(px, liq, dex, p.optString("pairAddress").ifEmpty { null })
            out.merge(token, cand) { a, b -> if (better(b, a)) b else a }
        }
    }

    // ---------------------------------------------------------------- GeckoTerminal
    private const val GECKO_THREADS = 4

    /**
     * Pool più liquida di ogni token e liquidità totale da GeckoTerminal (richieste parallele
     * da 30 indirizzi). Completa DexScreener: trova le pool che non indicizza. -> (prezzi, richieste fallite)
     */
    fun gecko(addresses: List<String>): Pair<Map<String, PoolPrice>, Int> {
        val out = ConcurrentHashMap<String, PoolPrice>()
        val failed = AtomicInteger(0)
        val chunks = addresses.chunked(30)
        if (chunks.isEmpty()) return Pair(out, 0)
        val headers = mapOf("Accept" to "application/json;version=20230302")
        val pool = Executors.newFixedThreadPool(minOf(GECKO_THREADS, chunks.size))
        try {
            for (chunk in chunks) {
                pool.execute {
                    val url = "https://api.geckoterminal.com/api/v2/networks/base/tokens/multi/" +
                        chunk.joinToString(",") + "?include=top_pools"
                    var text: String? = null
                    for (attempt in 0..2) {
                        try {
                            text = request(url, headers = headers)
                            break
                        } catch (e: HttpError) {
                            if (e.status != 429 || attempt == 2) break
                            Thread.sleep(1_500L * (attempt + 1))   // limite di frequenza
                        } catch (e: Exception) {
                            break
                        }
                    }
                    val body = text
                    if (body == null) {
                        failed.incrementAndGet()
                    } else {
                        try {
                            parseGecko(JSONObject(body), out)
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                        }
                    }
                }
            }
        } finally {
            pool.shutdown()
            pool.awaitTermination(120, TimeUnit.SECONDS)
        }
        return Pair(out, failed.get())
    }

    private fun parseGecko(o: JSONObject, out: ConcurrentHashMap<String, PoolPrice>) {
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
            var reserve = 0.0
            var top: JSONObject? = null
            var topReserve = -1.0
            for (j in 0 until refs.length()) {
                val p = pools[refs.getJSONObject(j).optString("id")] ?: continue
                val r = p.optJSONObject("attributes")?.optString("reserve_in_usd")?.toDoubleOrNull() ?: 0.0
                reserve += r
                if (r > topReserve) {
                    topReserve = r
                    top = p
                }
            }
            // prezzo del token nella sua pool principale; in mancanza, il prezzo medio del token
            var price: Double? = null
            if (top != null) {
                val pa = top.optJSONObject("attributes")
                val baseId = top.optJSONObject("relationships")?.optJSONObject("base_token")
                    ?.optJSONObject("data")?.optString("id")?.lowercase() ?: ""
                price = if (baseId.endsWith(addr)) pa?.optString("base_token_price_usd")?.toDoubleOrNull()
                else pa?.optString("quote_token_price_usd")?.toDoubleOrNull()
            }
            if (price == null || !(price > 0)) price = a.optString("price_usd").toDoubleOrNull()
            if (price == null || !(price > 0) || !price.isFinite()) continue
            val dexId = top?.optJSONObject("relationships")?.optJSONObject("dex")
                ?.optJSONObject("data")?.optString("id") ?: "geckoterminal"
            val pairAddr = top?.optJSONObject("attributes")?.optString("address")
            out[addr] = PoolPrice(price, reserve, dexId, pairAddr)
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

    /** Quota eseguibile on-chain su Base (Aero, Aerodrome, Uniswap, ...): fee pool e slippage inclusi. */
    fun quote(tokenIn: String, decIn: Int, tokenOut: String, decOut: Int, amountIn: Double): DexQuote {
        val raw = BigDecimal.valueOf(amountIn).movePointRight(decIn).toBigInteger()
        val url = "$KYBER?tokenIn=$tokenIn&tokenOut=$tokenOut&amountIn=$raw"
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
            val gas = rs.optString("gasUsd", "0").toDoubleOrNull() ?: 0.0
            val l1 = rs.optString("l1FeeUsd", "0").toDoubleOrNull() ?: 0.0
            return DexQuote(amountIn, out, gas + l1)
        }
        throw RuntimeException("KyberSwap: troppi tentativi")
    }
}

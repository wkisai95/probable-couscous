package com.wkisai.tokenarb

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Chiamate HTTP alle fonti dati (Coinbase, Yahoo, Chainlink via RPC, KyberSwap). */
object Api {
    private const val UA = "Mozilla/5.0 (Android) TokenArb/1.0"
    const val USDC = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
    const val USDC_DECIMALS = 6
    private const val BASE_RPC = "https://mainnet.base.org"
    private const val KYBER = "https://aggregator-api.kyberswap.com/base/api/v1/routes"
    private const val KYBER_DELAY_MS = 350L
    private var lastKyber = 0L

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
            if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
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

    // ---------------------------------------------------------------- KyberSwap
    /** Quota eseguibile on-chain su Base (Aerodrome, Uniswap, ...): fee pool e slippage inclusi. */
    @Synchronized
    fun quote(tokenIn: String, decIn: Int, tokenOut: String, decOut: Int, amountIn: Double): DexQuote {
        val wait = KYBER_DELAY_MS - (System.currentTimeMillis() - lastKyber)
        if (wait > 0) Thread.sleep(wait)
        val raw = BigDecimal.valueOf(amountIn).movePointRight(decIn).toBigInteger()
        val url = "$KYBER?tokenIn=$tokenIn&tokenOut=$tokenOut&amountIn=$raw"
        try {
            val body = JSONObject(request(url, headers = mapOf("X-Client-Id" to "tokenarb-android")))
            if (body.optInt("code", 0) != 0 || !body.has("data")) {
                throw RuntimeException("KyberSwap: ${body.optString("message")}")
            }
            val rs = body.getJSONObject("data").getJSONObject("routeSummary")
            val out = BigDecimal(rs.getString("amountOut")).movePointLeft(decOut).toDouble()
            val gas = rs.optString("gasUsd", "0").toDoubleOrNull() ?: 0.0
            val l1 = rs.optString("l1FeeUsd", "0").toDoubleOrNull() ?: 0.0
            return DexQuote(amountIn, out, gas + l1)
        } finally {
            lastKyber = System.currentTimeMillis()
        }
    }
}

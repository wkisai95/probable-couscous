package com.wkisai.tokenarb

import org.json.JSONArray
import org.json.JSONObject

/** Double opzionale da JSON (assente, null o non finito -> null). */
fun JSONObject.optD(k: String): Double? =
    if (has(k) && !isNull(k)) optDouble(k).takeIf { it.isFinite() } else null

/** Aggiunge un Double solo se presente e finito (org.json rifiuta NaN). */
fun JSONObject.putD(k: String, v: Double?): JSONObject {
    if (v != null && v.isFinite()) put(k, v)
    return this
}

/** Stock tokenizzato emesso da Coinbase (B20) su Base. */
data class CbToken(
    val address: String,
    val symbol: String,
    val ticker: String,
    val name: String,
    val decimals: Int,
    val multiplier: Double,
    val pausedFeatures: List<Int> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("a", address); put("s", symbol); put("t", ticker); put("n", name)
        put("d", decimals); put("m", multiplier); put("p", JSONArray(pausedFeatures))
    }

    companion object {
        fun fromJson(o: JSONObject): CbToken {
            val p = o.optJSONArray("p") ?: JSONArray()
            return CbToken(o.getString("a"), o.getString("s"), o.getString("t"), o.optString("n"),
                o.getInt("d"), o.getDouble("m"), (0 until p.length()).map { p.getInt(it) })
        }
    }
}

data class DexQuote(val amountIn: Double, val amountOut: Double, val gasUsd: Double)

data class RefQuote(
    val ticker: String,
    val price: Double,
    val asOfMillis: Long,
    val source: String,
    val perToken: Boolean = false,
)

/** Liquidità e prezzo medio del pool principale (GeckoTerminal). */
data class PoolData(val priceUsd: Double?, val reserveUsd: Double, val hasPools: Boolean)

data class SizeResult(
    val direction: String,
    val notionalUsd: Double,
    val tokens: Double,
    val execPrice: Double,
    val fairPrice: Double,
    val grossUsd: Double,
    val costsUsd: Double,
    val netUsd: Double,
    val netBps: Double,
    val ladder: List<Pair<Double, Double>> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("direction", direction); put("notional", notionalUsd); put("tokens", tokens)
        put("exec", execPrice); put("fair", fairPrice); put("gross", grossUsd)
        put("costs", costsUsd); put("net", netUsd); put("netBps", netBps)
        put("ladder", JSONArray().apply {
            ladder.forEach { put(JSONArray().put(it.first).put(it.second)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject?): SizeResult? {
            if (o == null) return null
            val l = o.optJSONArray("ladder") ?: JSONArray()
            return SizeResult(
                o.getString("direction"), o.getDouble("notional"), o.getDouble("tokens"),
                o.getDouble("exec"), o.getDouble("fair"), o.getDouble("gross"),
                o.getDouble("costs"), o.getDouble("net"), o.getDouble("netBps"),
                (0 until l.length()).map { l.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } },
            )
        }
    }
}

data class Opportunity(
    val ticker: String,
    val symbol: String,
    val address: String,
    val session: String,
    val fairPrice: Double,       // prezzo di Borsa per token
    val refSource: String,
    val refAgeMin: Double,
    val dexBuy: Double?,         // prezzo eseguibile di acquisto (size piccola), se quotato
    val dexSell: Double?,        // prezzo eseguibile di vendita (size piccola), se quotato
    val poolPrice: Double?,      // prezzo medio del pool principale (indicativo)
    val liquidityUsd: Double?,
    val gapBps: Double,
    val chainlink: Double?,
    val best: SizeResult?,
    val fixed: SizeResult?,
    val actionable: Boolean,
    val notes: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ticker", ticker); put("symbol", symbol); put("address", address)
        put("session", session); put("fair", fairPrice); put("refSource", refSource)
        put("refAge", refAgeMin); put("gap", gapBps)
        putD("buy", dexBuy); putD("sell", dexSell); putD("pool", poolPrice)
        putD("liq", liquidityUsd); putD("chainlink", chainlink)
        best?.let { put("best", it.toJson()) }
        fixed?.let { put("fixed", it.toJson()) }
        put("actionable", actionable)
        put("notes", JSONArray(notes))
    }

    companion object {
        fun fromJson(o: JSONObject): Opportunity {
            val n = o.optJSONArray("notes") ?: JSONArray()
            return Opportunity(
                o.getString("ticker"), o.getString("symbol"), o.getString("address"),
                o.getString("session"), o.getDouble("fair"), o.getString("refSource"),
                o.getDouble("refAge"), o.optD("buy"), o.optD("sell"), o.optD("pool"),
                o.optD("liq"), o.getDouble("gap"), o.optD("chainlink"),
                SizeResult.fromJson(o.optJSONObject("best")),
                SizeResult.fromJson(o.optJSONObject("fixed")),
                o.optBoolean("actionable"),
                (0 until n.length()).map { n.getString(it) },
            )
        }
    }
}

data class ScanOutcome(
    val timeMillis: Long,
    val session: String,
    val tokensFound: Int,
    val opportunities: List<Opportunity>,
    val errors: List<String>,
    val inProgress: Boolean = false,
    val progress: String = "",
    val durationSec: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", timeMillis); put("session", session); put("tokens", tokensFound)
        put("opps", JSONArray().apply { opportunities.forEach { put(it.toJson()) } })
        put("errors", JSONArray(errors))
        put("inProgress", inProgress); put("progress", progress); put("duration", durationSec)
    }

    companion object {
        fun fromJson(o: JSONObject): ScanOutcome {
            val a = o.optJSONArray("opps") ?: JSONArray()
            val e = o.optJSONArray("errors") ?: JSONArray()
            return ScanOutcome(
                o.getLong("time"), o.getString("session"), o.optInt("tokens"),
                (0 until a.length()).mapNotNull {
                    try { Opportunity.fromJson(a.getJSONObject(it)) } catch (x: Exception) { null }
                },
                (0 until e.length()).map { e.getString(it) },
                o.optBoolean("inProgress"), o.optString("progress"), o.optInt("duration"),
            )
        }
    }
}

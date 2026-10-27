package com.wkisai.tokenarb

import org.json.JSONArray
import org.json.JSONObject

/** Stock tokenizzato emesso da Coinbase (B20) su Base. */
data class CbToken(
    val address: String,
    val symbol: String,
    val ticker: String,
    val name: String,
    val decimals: Int,
    val multiplier: Double,
    val pausedFeatures: List<Int> = emptyList(),
)

data class DexQuote(val amountIn: Double, val amountOut: Double, val gasUsd: Double)

data class RefQuote(
    val ticker: String,
    val price: Double,
    val asOfMillis: Long,
    val source: String,
    val perToken: Boolean = false,
)

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
    val fairPrice: Double,
    val refSource: String,
    val refAgeMin: Double,
    val dexBuy: Double,
    val dexSell: Double,
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
        put("refAge", refAgeMin); put("buy", dexBuy); put("sell", dexSell); put("gap", gapBps)
        if (chainlink != null) put("chainlink", chainlink)
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
                o.getDouble("refAge"), o.getDouble("buy"), o.getDouble("sell"), o.getDouble("gap"),
                if (o.has("chainlink")) o.getDouble("chainlink") else null,
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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("time", timeMillis); put("session", session); put("tokens", tokensFound)
        put("opps", JSONArray().apply { opportunities.forEach { put(it.toJson()) } })
        put("errors", JSONArray(errors))
    }

    companion object {
        fun fromJson(o: JSONObject): ScanOutcome {
            val a = o.optJSONArray("opps") ?: JSONArray()
            val e = o.optJSONArray("errors") ?: JSONArray()
            return ScanOutcome(
                o.getLong("time"), o.getString("session"), o.optInt("tokens"),
                (0 until a.length()).map { Opportunity.fromJson(a.getJSONObject(it)) },
                (0 until e.length()).map { e.getString(it) },
            )
        }
    }
}

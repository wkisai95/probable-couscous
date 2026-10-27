package com.wkisai.tokenarb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Impostazioni modificabili dall'app. */
data class Settings(
    val maxNotional: Double = 25_000.0,
    val tradeSize: Double = 1_000.0,
    val minProfit: Double = 20.0,
    val minBps: Double = 30.0,
    val feePerShare: Double = 0.005,
    val feeBps: Double = 0.0,
    val fixedCost: Double = 0.0,
    val halfSpreadRegular: Double = 2.0,
    val halfSpreadExtended: Double = 15.0,
    val maxRefAgeMin: Double = 20.0,
    val cooldownMin: Double = 60.0,
    val extendedTradable: Boolean = false,
    val notifications: Boolean = true,
    val tickers: String = "",          // vuoto = tutti
    val exclude: String = "",
) {
    fun tickerSet() = tickers.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    fun excludeSet() = exclude.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
}

object Store {
    private const val PREFS = "tokenarb"
    const val KEY_RESULTS = "last_scan"
    private const val KEY_ALERTS = "alerts"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun SharedPreferences.d(k: String, def: Double) =
        getString(k, null)?.replace(',', '.')?.toDoubleOrNull() ?: def

    fun loadSettings(ctx: Context): Settings {
        val p = prefs(ctx)
        val d = Settings()
        return Settings(
            maxNotional = p.d("maxNotional", d.maxNotional),
            tradeSize = p.d("tradeSize", d.tradeSize),
            minProfit = p.d("minProfit", d.minProfit),
            minBps = p.d("minBps", d.minBps),
            feePerShare = p.d("feePerShare", d.feePerShare),
            feeBps = p.d("feeBps", d.feeBps),
            fixedCost = p.d("fixedCost", d.fixedCost),
            halfSpreadRegular = p.d("halfSpreadRegular", d.halfSpreadRegular),
            halfSpreadExtended = p.d("halfSpreadExtended", d.halfSpreadExtended),
            maxRefAgeMin = p.d("maxRefAgeMin", d.maxRefAgeMin),
            cooldownMin = p.d("cooldownMin", d.cooldownMin),
            extendedTradable = p.getBoolean("extendedTradable", d.extendedTradable),
            notifications = p.getBoolean("notifications", d.notifications),
            tickers = p.getString("tickers", d.tickers) ?: "",
            exclude = p.getString("exclude", d.exclude) ?: "",
        )
    }

    fun saveResults(ctx: Context, o: ScanOutcome) {
        prefs(ctx).edit().putString(KEY_RESULTS, o.toJson().toString()).apply()
    }

    fun loadResults(ctx: Context): ScanOutcome? = try {
        prefs(ctx).getString(KEY_RESULTS, null)?.let { ScanOutcome.fromJson(JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    fun loadAlerts(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString(KEY_ALERTS, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun saveAlerts(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString(KEY_ALERTS, o.toString()).apply()
    }
}

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
    val buyOnly: Boolean = true,       // solo arbitraggi in cui conviene comprare il token
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
            buyOnly = p.getBoolean("buyOnly", d.buyOnly),
            tickers = p.getString("tickers", d.tickers) ?: "",
            exclude = p.getString("exclude", d.exclude) ?: "",
        )
    }

    fun saveResults(ctx: Context, o: ScanOutcome) {
        prefs(ctx).edit().putString(KEY_RESULTS, o.toJson().toString()).apply()
    }

    /** Conserva l'ultima lista valida e registra l'errore tra le note. */
    fun saveFailure(ctx: Context, e: Exception) {
        val prev = loadResults(ctx)
        saveResults(ctx, ScanOutcome(
            System.currentTimeMillis(), Market.session(), prev?.tokensFound ?: 0,
            prev?.opportunities ?: emptyList(),
            listOf("Scansione fallita: ${e.message ?: e.javaClass.simpleName}"),
        ))
    }

    fun loadResults(ctx: Context): ScanOutcome? = try {
        prefs(ctx).getString(KEY_RESULTS, null)?.let { ScanOutcome.fromJson(JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    /** Stato tra le scansioni: elenco token in cache e token senza liquidità. */
    fun loadState(ctx: Context): Scanner.State {
        val p = prefs(ctx)
        return Scanner.State(
            p.getString("tokens_cache", null),
            p.getLong("tokens_cache_at", 0L),
            java.util.concurrent.ConcurrentHashMap(loadNoRoute(ctx)),
        )
    }

    fun saveState(ctx: Context, st: Scanner.State) {
        prefs(ctx).edit()
            .putString("tokens_cache", st.tokensJson)
            .putLong("tokens_cache_at", st.tokensAt)
            .apply()
        saveNoRoute(ctx, st.noRoute)
    }

    /** Token senza liquidità on-chain: indirizzo -> momento in cui ricontrollarli. */
    fun loadNoRoute(ctx: Context): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        try {
            val o = JSONObject(prefs(ctx).getString("no_route", "{}") ?: "{}")
            o.keys().forEach { k -> out[k] = o.getLong(k) }
        } catch (e: Exception) {
            // stato illeggibile: si riparte da zero
        }
        return out
    }

    fun saveNoRoute(ctx: Context, m: Map<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString("no_route", o.toString()).apply()
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

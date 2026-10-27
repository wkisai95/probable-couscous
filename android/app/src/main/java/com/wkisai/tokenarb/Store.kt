package com.wkisai.tokenarb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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
    val closeTolBps: Double = 10.0,    // gap considerato chiuso entro questi bps
    val minExitPct: Double = 10.0,     // avvisa se si può vendere almeno questa % della posizione
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
    const val KEY_POSITIONS = "positions"
    const val KEY_POS_STATUS = "position_status"
    private const val KEY_ALERTS = "alerts"
    private val lock = Any()

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
            closeTolBps = p.d("closeTolBps", d.closeTolBps),
            minExitPct = p.d("minExitPct", d.minExitPct),
            extendedTradable = p.getBoolean("extendedTradable", d.extendedTradable),
            notifications = p.getBoolean("notifications", d.notifications),
            buyOnly = p.getBoolean("buyOnly", d.buyOnly),
            tickers = p.getString("tickers", d.tickers) ?: "",
            exclude = p.getString("exclude", d.exclude) ?: "",
        )
    }

    // ------------------------------------------------------------ risultati
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

    // ------------------------------------------------------------ stato tra le scansioni
    /** Elenco token in cache, token senza percorso di scambio, scoperta GeckoTerminal in cache. */
    fun loadState(ctx: Context): Scanner.State {
        val p = prefs(ctx)
        return Scanner.State(
            tokensJson = p.getString("tokens_cache", null),
            tokensAt = p.getLong("tokens_cache_at", 0L),
            noRoute = ConcurrentHashMap(loadNoRoute(ctx)),
            discoveryJson = p.getString("discovery_cache", null),
            discoveryAt = p.getLong("discovery_cache_at", 0L),
        )
    }

    fun saveState(ctx: Context, st: Scanner.State) {
        prefs(ctx).edit()
            .putString("tokens_cache", st.tokensJson)
            .putLong("tokens_cache_at", st.tokensAt)
            .putString("discovery_cache", st.discoveryJson)
            .putLong("discovery_cache_at", st.discoveryAt)
            .apply()
        saveNoRoute(ctx, st.noRoute)
    }

    private fun loadNoRoute(ctx: Context): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        try {
            val o = JSONObject(prefs(ctx).getString("no_route", "{}") ?: "{}")
            o.keys().forEach { k -> out[k] = o.getLong(k) }
        } catch (e: Exception) {
            // stato illeggibile: si riparte da zero
        }
        return out
    }

    private fun saveNoRoute(ctx: Context, m: Map<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString("no_route", o.toString()).apply()
    }

    // ------------------------------------------------------------ posizioni
    fun loadPositions(ctx: Context): List<Position> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY_POSITIONS, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull {
            try { Position.fromJson(arr.getJSONObject(it)) } catch (e: Exception) { null }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun savePositions(ctx: Context, list: List<Position>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_POSITIONS, arr.toString()).commit()
    }

    fun addPosition(ctx: Context, p: Position) = synchronized(lock) {
        savePositions(ctx, loadPositions(ctx) + p)
    }

    /** Modifica una posizione; se [transform] restituisce null la posizione viene eliminata. */
    fun updatePosition(ctx: Context, id: String, transform: (Position) -> Position?) = synchronized(lock) {
        val list = loadPositions(ctx).mapNotNull { if (it.id == id) transform(it) else it }
        savePositions(ctx, list)
        // dopo una vendita o un'eliminazione gli avvisi ripartono da zero
        val st = loadStatusesUnlocked(ctx).toMutableMap()
        st.remove(id)
        saveStatuses(ctx, st.filterKeys { k -> list.any { it.id == k } })
    }

    fun loadStatuses(ctx: Context): Map<String, PositionStatus> = synchronized(lock) { loadStatusesUnlocked(ctx) }

    private fun loadStatusesUnlocked(ctx: Context): Map<String, PositionStatus> = try {
        val o = JSONObject(prefs(ctx).getString(KEY_POS_STATUS, "{}") ?: "{}")
        val out = mutableMapOf<String, PositionStatus>()
        o.keys().forEach { k ->
            val st = try { PositionStatus.fromJson(o.getJSONObject(k)) } catch (e: Exception) { null }
            if (st != null) out[k] = st
        }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    private fun saveStatuses(ctx: Context, m: Map<String, PositionStatus>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v.toJson()) }
        prefs(ctx).edit().putString(KEY_POS_STATUS, o.toString()).commit()
    }

    /** Aggiorna gli esiti dei controlli, solo per le posizioni ancora presenti. */
    fun mergeStatuses(ctx: Context, updates: List<PositionStatus>) = synchronized(lock) {
        val ids = loadPositions(ctx).map { it.id }.toSet()
        val m = loadStatusesUnlocked(ctx).toMutableMap()
        updates.forEach { if (it.id in ids) m[it.id] = it }
        saveStatuses(ctx, m.filterKeys { it in ids })
    }

    // ------------------------------------------------------------ avvisi opportunità
    fun loadAlerts(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString(KEY_ALERTS, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun saveAlerts(ctx: Context, o: JSONObject) {
        prefs(ctx).edit().putString(KEY_ALERTS, o.toString()).apply()
    }
}

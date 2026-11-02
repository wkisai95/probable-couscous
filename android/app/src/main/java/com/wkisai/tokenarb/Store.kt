package com.wkisai.tokenarb

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Store {
    private const val PREFS = "tokenarb"
    private const val KEY_RESULTS = "last_scan"   // solo per ripulire le versioni precedenti
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
            coinbase = p.getBoolean("coinbase", d.coinbase),
            robinhood = p.getBoolean("robinhood", d.robinhood),
            tickers = p.getString("tickers", d.tickers) ?: "",
            exclude = p.getString("exclude", d.exclude) ?: "",
        )
    }

    // ------------------------------------------------------------ file
    // I dati grandi (risultati, elenco token) stanno in file, non nelle preferenze: le preferenze
    // vengono riscritte per intero a ogni modifica e, se pesanti, bloccano l'interfaccia.
    private const val RESULTS_FILE = "last_scan.json"
    private const val TOKENS_FILE = "tokens_cache.json"

    private fun file(ctx: Context, name: String) = File(ctx.applicationContext.filesDir, name)

    private fun writeAtomic(ctx: Context, name: String, text: String) {
        val target = file(ctx, name)
        val tmp = File(target.parentFile, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun readFile(ctx: Context, name: String): String? = try {
        file(ctx, name).takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ risultati
    /** Avanzamento della scansione: solo in memoria, nessuna scrittura su disco. */
    fun publishProgress(o: ScanOutcome) = LiveState.publish(o)

    /** Risultato finale: in memoria per la schermata e su file per i riavvii. */
    fun saveResults(ctx: Context, o: ScanOutcome) {
        LiveState.publish(o)
        try {
            writeAtomic(ctx, RESULTS_FILE, o.toJson().toString())
        } catch (e: Exception) {
            // il risultato resta comunque in memoria
        }
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

    /** Ultimo risultato: dalla memoria o, dopo un riavvio, dal file. Da chiamare fuori dal thread UI. */
    fun loadResults(ctx: Context): ScanOutcome? {
        LiveState.outcome?.let { return it }
        val fromDisk = readFile(ctx, RESULTS_FILE)?.let {
            try { ScanOutcome.fromJson(JSONObject(it)) } catch (e: Exception) { null }
        }
        if (fromDisk != null) LiveState.restore(fromDisk)
        return LiveState.outcome
    }

    // ------------------------------------------------------------ stato tra le scansioni
    /** Elenco token in cache (file) e token senza percorso o senza pool (preferenze). */
    fun loadState(ctx: Context): Scanner.State {
        val p = prefs(ctx)
        // pulizia delle versioni precedenti, che tenevano dati grandi nelle preferenze
        if (p.contains(KEY_RESULTS) || p.contains("tokens_cache") || p.contains("discovery_cache")) {
            p.edit().remove(KEY_RESULTS).remove("tokens_cache").remove("tokens_cache_at")
                .remove("discovery_cache").remove("discovery_cache_at").apply()
        }
        return Scanner.State(
            tokensJson = readFile(ctx, TOKENS_FILE),
            tokensAt = p.getLong("tokens_file_at", 0L),
            noRoute = ConcurrentHashMap(loadTimes(ctx, "no_route")),
            noPools = ConcurrentHashMap(loadTimes(ctx, "no_pools")),
        )
    }

    fun saveState(ctx: Context, st: Scanner.State) {
        val p = prefs(ctx)
        val json = st.tokensJson
        if (json != null && st.tokensAt != p.getLong("tokens_file_at", 0L)) {
            try {
                writeAtomic(ctx, TOKENS_FILE, json)
                p.edit().putLong("tokens_file_at", st.tokensAt).apply()
            } catch (e: Exception) {
                // senza cache si riscarica l'elenco alla prossima scansione
            }
        }
        saveTimes(ctx, "no_route", st.noRoute)
        saveTimes(ctx, "no_pools", st.noPools)
    }

    /** Mappa indirizzo -> momento in cui ricontrollare (token senza percorso o senza pool). */
    private fun loadTimes(ctx: Context, key: String): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        try {
            val o = JSONObject(prefs(ctx).getString(key, "{}") ?: "{}")
            o.keys().forEach { k -> out[k] = o.getLong(k) }
        } catch (e: Exception) {
            // stato illeggibile: si riparte da zero
        }
        return out
    }

    private fun saveTimes(ctx: Context, key: String, m: Map<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(key, o.toString()).apply()
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

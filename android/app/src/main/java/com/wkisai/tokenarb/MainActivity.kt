package com.wkisai.tokenarb

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var posBox: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var batteryBtn: Button
    private val expanded = mutableSetOf<String>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_RESULTS || key == Store.KEY_POSITIONS || key == Store.KEY_POS_STATUS) {
            runOnUiThread { render() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(8)) }
        scanBtn = Button(this).apply {
            text = "Scansiona ora"
            setOnClickListener { scanNow() }
        }
        batteryBtn = Button(this).apply {
            text = "Consenti scansioni in background"
            setOnClickListener { askBatteryExemption() }
        }
        posBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        root.addView(scanBtn)
        root.addView(batteryBtn)
        root.addView(posBox)
        root.addView(list)
        setContentView(ScrollView(this).apply { addView(root) })

        Notifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ScanWorker.schedule(this)
        if (Store.loadResults(this) == null) scanNow()
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        val pm = getSystemService(PowerManager::class.java)
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        render()
    }

    override fun onPause() {
        super.onPause()
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.settings))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            startActivity(Intent(this, SettingsActivity::class.java)); return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun askBatteryExemption() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun scanNow() {
        if (Scanner.isRunning) {
            toast("Scansione già in corso: i risultati si aggiornano qui")
            return
        }
        scanBtn.isEnabled = false
        scanBtn.text = "Scansione in corso…"
        val ctx = applicationContext
        thread {
            ScanWorker.runScan(ctx)
            runOnUiThread { render() }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render() {
        val s = Store.loadSettings(this)
        renderPositions(s)
        renderOpportunities(s)
    }

    /** In modalità "solo acquisto": sconti in attesa di verifica, poi solo quelli con netto positivo. */
    private fun showInBuyOnly(o: Opportunity): Boolean {
        val b = o.best
        return if (!o.verified) o.gapBps < 0 && abs(o.gapBps) <= Scanner.MAX_PLAUSIBLE_GAP_BPS
        else b != null && b.direction == Sizing.BUY && b.netUsd > 0
    }

    private fun renderOpportunities(s: Settings) {
        val running = Scanner.isRunning
        scanBtn.isEnabled = !running
        scanBtn.text = if (running) "Scansione in corso…" else "Scansiona ora"
        val out = Store.loadResults(this)
        list.removeAllViews()
        if (out == null) {
            status.text = if (running) "⏳ Prima scansione in corso…"
            else "Nessuna scansione ancora. Le scansioni automatiche partono ogni 15 minuti."
            return
        }
        val time = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(Date(out.timeMillis))
        val shown = if (s.buyOnly) out.opportunities.filter { showInBuyOnly(it) } else out.opportunities
        val pending = shown.count { !it.verified }
        val actionable = shown.count { it.actionable }
        status.text = buildString {
            when {
                out.inProgress && running -> append("⏳ ${out.progress} · ${out.durationSec} s\n")
                out.inProgress -> append("Scansione interrotta: risultati parziali delle $time\n")
                else -> append("Ultima scansione: $time (${out.durationSec} s) · Borsa ${Market.label(out.session)}\n")
            }
            if (out.feedInfo.isNotEmpty()) append("Prezzi pool: ${out.feedInfo} · ")
            append("${out.tokensFound} token Coinbase · ${out.opportunities.size} con liquidità\n")
            if (s.buyOnly) {
                append("${shown.size - pending} da comprare con profitto")
                if (pending > 0) append(" · $pending in verifica on-chain")
                append(" · $actionable sopra soglia")
                if (shown.isEmpty() && !out.inProgress) {
                    val closest = out.opportunities.minByOrNull { it.gapBps }
                    append("\nNessun token conviene comprarlo adesso.")
                    if (closest != null && closest.gapBps < 0) {
                        append(" Il più scontato è ${closest.symbol} (${signed(closest.gapBps)} bps), ma non copre i costi.")
                    } else {
                        append(" Tutti i token costano quanto o più che in Borsa.")
                    }
                }
            } else {
                val sized = out.opportunities.count { it.best != null }
                append("$sized con size · $actionable sopra soglia")
            }
            if (out.errors.isNotEmpty()) append("\nℹ️ ${out.errors.size} note (tocca qui per i dettagli)")
        }
        status.setOnClickListener {
            if (out.errors.isNotEmpty()) {
                AlertDialog.Builder(this).setTitle("Note sulla scansione")
                    .setMessage(out.errors.joinToString("\n\n")).setPositiveButton("OK", null).show()
            }
        }
        if (Store.loadPositions(this).isNotEmpty() && shown.isNotEmpty()) {
            list.addView(sectionTitle(if (s.buyOnly) "Token da comprare" else "Arbitraggi"))
        }
        for (o in shown) list.addView(card(o))
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.BLACK)
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun box(color: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(color))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
    }

    private fun header(left: String, right: String, rightColor: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(this@MainActivity).apply {
            text = left
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@MainActivity).apply {
            text = right
            textSize = 15f
            gravity = Gravity.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(rightColor)
        })
    }

    private fun body(t: String, size: Float = 13f) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(Color.DKGRAY)
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
    }

    private fun card(o: Opportunity): View {
        val b = o.best
        val c = box(when {
            o.actionable -> "#E3F6E8"
            b != null && b.netUsd > 0 -> "#FFF6DD"
            !o.verified -> "#EEF3FF"
            else -> "#F2F4F7"
        })
        val right = when {
            b != null -> "netto ${Notifier.usd(b.netUsd)}"
            o.gapBps < 0 -> "sconto ${(-o.gapBps).toInt()} bps"
            else -> "gap ${signed(o.gapBps)} bps"
        }
        val rightColor = when {
            b != null && b.netUsd > 0 -> Color.parseColor("#0A7A35")
            !o.verified -> Color.parseColor("#1F4FB8")
            else -> Color.DKGRAY
        }
        c.addView(header((if (o.actionable) "⚡ " else "") + o.symbol, right, rightColor))
        c.addView(body(buildString {
            if (o.gapBps < 0) append("Sconto ${(-o.gapBps).toInt()} bps sulla Borsa · ")
            else append("Gap ${signed(o.gapBps)} bps · ")
            append("Borsa ${Notifier.px(o.fairPrice)} · ${Notifier.dexText(o)}")
            if (b != null) {
                append("\n${Notifier.directionLabel(b.direction)} · size ${Notifier.usd0(b.notionalUsd)} (${b.netBps.toInt()} bps)")
            }
            if (!o.verified) append("\n⏳ verifica on-chain di prezzo e size in corso…")
        }))
        val details = body(buildString {
            append("Prezzo di Borsa: ${o.refSource} (${o.refAgeMin.toInt()} min fa)")
            o.chainlink?.let { append(" · Chainlink ${Notifier.px(it)}") }
            o.poolPrice?.let { append("\nPool ${o.dex ?: ""}: ${Notifier.px(it)}") }
            o.liquidityUsd?.let { append(" · liquidità ${Notifier.usd0(it)}") }
            if (b != null) {
                append("\nPrezzo medio on-chain ${Notifier.px(b.execPrice)} · token ${Notifier.qty(b.tokens)}")
                append("\nLordo ${Notifier.usd(b.grossUsd)} − costi ${Notifier.usd(b.costsUsd)} = netto ${Notifier.usd(b.netUsd)}")
                if (b.ladder.isNotEmpty()) append("\nScala size→netto: " +
                    b.ladder.joinToString(", ") { "${Notifier.usd0(it.first)}→${Notifier.usd0(it.second)}" })
            }
            o.fixed?.let { append("\nCon ${Notifier.usd0(it.notionalUsd)}: netto ${Notifier.usd(it.netUsd)}") }
            if (o.notes.isNotEmpty()) append("\n" + o.notes.joinToString("\n"))
            append("\nTieni premuto per aprire la pool su DexScreener")
        }, 12f).apply {
            setPadding(0, dp(6), 0, 0)
            visibility = if (o.address in expanded) View.VISIBLE else View.GONE
        }
        c.addView(details)
        if (o.gapBps < 0 || b?.direction == Sizing.BUY) {
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
                addView(smallButton("Ho comprato") { boughtDialog(o) })
            })
        }
        c.setOnClickListener {
            if (details.visibility == View.VISIBLE) {
                details.visibility = View.GONE; expanded -= o.address
            } else {
                details.visibility = View.VISIBLE; expanded += o.address
            }
        }
        c.setOnLongClickListener {
            val url = o.pair?.let { "https://dexscreener.com/base/$it" } ?: "https://basescan.org/token/${o.address}"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }
        return c
    }

    // ------------------------------------------------------------------ posizioni

    private fun renderPositions(s: Settings) {
        posBox.removeAllViews()
        val positions = Store.loadPositions(this)
        if (positions.isEmpty()) return
        val statuses = Store.loadStatuses(this)
        posBox.addView(sectionTitle("Le mie posizioni"))
        for (p in positions) posBox.addView(positionCard(p, statuses[p.id], s))
    }

    private fun positionCard(p: Position, st: PositionStatus?, s: Settings): View {
        val minF = max(0.1, s.minExitPct / 100.0) - 1e-9
        val sellable = st != null && st.fraction >= minF
        val c = box(if (sellable) "#E3F6E8" else "#EEF3FF")
        val right = if (sellable && st != null) "vendi il ${(st.fraction * 100).roundToInt()}%" else "in attesa"
        c.addView(header("💼 ${p.symbol}", right,
            if (sellable) Color.parseColor("#0A7A35") else Color.parseColor("#1F4FB8")))
        c.addView(body(buildString {
            append("Comprati ${Notifier.usd0(p.usd)} a ${Notifier.px(p.entryPrice)} · ${Notifier.qty(p.tokens)} token")
            if (st == null) {
                append("\nIn attesa del primo controllo: avvia una scansione o aspetta quella automatica.")
                return@buildString
            }
            val pool = st.poolPrice
            val fair = st.fair
            if (pool != null && fair != null) {
                val gap = (pool - fair) / fair * 1e4
                append("\nOra: pool ${Notifier.px(pool)} · Borsa ${Notifier.px(fair)} · gap ${signed(gap)} bps")
                val value = p.tokens * pool
                append("\nValore stimato ${Notifier.usd0(value)} (${Notifier.signedUsd(value - p.usd)})")
            }
            if (sellable) {
                append("\n✅ Puoi vendere ${Notifier.qty(st.sellTokens ?: 0.0)} token chiudendo il gap: ")
                append("incasso ~${Notifier.usd(st.proceedsUsd ?: 0.0)} (${Notifier.signedUsd(st.pnlUsd ?: 0.0)})")
            }
            st.note?.let { append("\n$it") }
            val time = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(st.at))
            append("\nControllo delle $time")
        }))
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(smallButton("Ho venduto") { soldDialog(p, st) })
            addView(smallButton("Elimina") { deleteDialog(p) })
        })
        return c
    }

    /** Numero digitato in italiano o in inglese: "1.250,50", "1250,5", "1250.5". */
    private fun parseNumber(s: String): Double? {
        var t = s.trim().replace(" ", "").replace("$", "").replace("%", "")
        if (t.contains(',') && t.contains('.')) t = t.replace(".", "").replace(',', '.')
        else t = t.replace(',', '.')
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun field(container: LinearLayout, label: String, value: String): EditText {
        container.addView(TextView(this).apply { text = label; setPadding(0, dp(8), 0, 0) })
        val e = EditText(this).apply {
            setText(value)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelectAllOnFocus(true)
        }
        container.addView(e)
        return e
    }

    private fun boughtDialog(o: Opportunity) {
        val s = Store.loadSettings(this)
        val b = o.best
        val defUsd = b?.notionalUsd ?: s.tradeSize
        val defPx = b?.execPrice ?: o.dexBuy ?: o.poolPrice ?: o.fairPrice
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val usdIn = field(form, "Quanto hai speso (USD)", String.format(Locale.US, "%.0f", defUsd))
        val pxIn = field(form, "Prezzo medio pagato per token", String.format(Locale.US, "%.4f", defPx))
        form.addView(TextView(this).apply {
            text = "Ti avviso quando potrai vendere almeno il ${s.minExitPct.toInt()}% chiudendo il gap con la Borsa."
            setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("Ho comprato ${o.symbol}")
            .setView(form)
            .setPositiveButton("Salva") { _, _ ->
                val usd = parseNumber(usdIn.text.toString())
                val px = parseNumber(pxIn.text.toString())
                if (usd == null || px == null || usd <= 0 || px <= 0) {
                    toast("Importo o prezzo non validi")
                } else {
                    Store.addPosition(this, Position(
                        UUID.randomUUID().toString(), o.address, o.symbol, o.ticker,
                        usd, px, usd / px, System.currentTimeMillis(),
                    ))
                    toast("Posizione salvata: ti avviso quando il gap si chiude")
                    render()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun soldDialog(p: Position, st: PositionStatus?) {
        val def = if (st != null && st.fraction > 0) st.fraction * 100 else 100.0
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val pctIn = field(form, "Percentuale della posizione venduta", String.format(Locale.US, "%.0f", def))
        AlertDialog.Builder(this)
            .setTitle("Ho venduto ${p.symbol}")
            .setView(form)
            .setPositiveButton("Salva") { _, _ ->
                val pct = parseNumber(pctIn.text.toString())
                if (pct == null || pct <= 0 || pct > 100) {
                    toast("Percentuale non valida")
                } else if (pct >= 99.5) {
                    Store.updatePosition(this, p.id) { null }
                    toast("Posizione chiusa")
                } else {
                    val keep = 1 - pct / 100
                    Store.updatePosition(this, p.id) { it.copy(usd = it.usd * keep, tokens = it.tokens * keep) }
                    toast("Restano ${Notifier.qty(p.tokens * keep)} token")
                }
                render()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun deleteDialog(p: Position) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare ${p.symbol}?")
            .setMessage("La posizione non verrà più monitorata.")
            .setPositiveButton("Elimina") { _, _ ->
                Store.updatePosition(this, p.id) { null }
                render()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun signed(v: Double) = (if (v > 0) "+" else "") + v.toInt()
}

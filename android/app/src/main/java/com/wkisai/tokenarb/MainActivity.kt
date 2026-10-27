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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var batteryBtn: Button
    private val expanded = mutableSetOf<String>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Store.KEY_RESULTS) runOnUiThread { render() }
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
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(status)
        root.addView(scanBtn)
        root.addView(batteryBtn)
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

    private fun scanNow() {
        if (Scanner.isRunning) {
            Toast.makeText(this, "Scansione già in corso: i risultati si aggiornano qui", Toast.LENGTH_SHORT).show()
            return
        }
        scanBtn.isEnabled = false
        scanBtn.text = "Scansione in corso…"
        val ctx = applicationContext
        val s = Store.loadSettings(ctx)
        thread {
            val st = Store.loadState(ctx)
            try {
                // i risultati parziali arrivano allo schermo tramite le preferenze condivise
                val out = Scanner.runExclusive(s, st) { partial -> Store.saveResults(ctx, partial) }
                if (out != null) {
                    Store.saveState(ctx, st)
                    Store.saveResults(ctx, out)
                    Notifier.notify(ctx, out, s)
                }
            } catch (e: Exception) {
                Store.saveFailure(ctx, e)
            } finally {
                runOnUiThread { render() }
            }
        }
    }

    private fun render() {
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
        val actionable = out.opportunities.count { it.actionable }
        val sized = out.opportunities.count { it.best != null }
        status.text = buildString {
            when {
                out.inProgress && running -> append("⏳ ${out.progress} · ${out.durationSec} s\n")
                out.inProgress -> append("Scansione interrotta: risultati parziali delle $time\n")
                else -> append("Ultima scansione: $time (${out.durationSec} s) · Borsa ${Market.label(out.session)}\n")
            }
            append("${out.tokensFound} token Coinbase · ${out.opportunities.size} con liquidità · ")
            append("$sized con size · $actionable sopra soglia")
            if (out.errors.isNotEmpty()) append("\nℹ️ ${out.errors.size} note (tocca qui per i dettagli)")
        }
        status.setOnClickListener {
            if (out.errors.isNotEmpty()) {
                AlertDialog.Builder(this).setTitle("Note sulla scansione")
                    .setMessage(out.errors.joinToString("\n\n")).setPositiveButton("OK", null).show()
            }
        }
        for (o in out.opportunities) list.addView(card(o))
    }

    private fun card(o: Opportunity): View {
        val b = o.best
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(when {
                    o.actionable -> Color.parseColor("#E3F6E8")
                    b != null && b.netUsd > 0 -> Color.parseColor("#FFF6DD")
                    else -> Color.parseColor("#F2F4F7")
                })
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply {
            text = (if (o.actionable) "⚡ " else "") + o.symbol
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = if (b != null) "netto ${Notifier.usd(b.netUsd)}" else "gap ${signed(o.gapBps)} bps"
            textSize = 15f
            gravity = Gravity.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (b != null && b.netUsd > 0) Color.parseColor("#0A7A35") else Color.DKGRAY)
        })
        c.addView(head)
        c.addView(TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = buildString {
                append("Gap ${signed(o.gapBps)} bps · Borsa ${Notifier.px(o.fairPrice)} · ")
                append(Notifier.dexText(o))
                if (b != null) append("\n${Notifier.directionLabel(b.direction)} · size ${Notifier.usd0(b.notionalUsd)} (${b.netBps.toInt()} bps)")
            }
        })
        val details = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, 0)
            text = buildString {
                append("Prezzo di Borsa: ${o.refSource} (${o.refAgeMin.toInt()} min fa)")
                o.chainlink?.let { append(" · Chainlink ${Notifier.px(it)}") }
                o.poolPrice?.let { append("\nPrezzo medio del pool ${Notifier.px(it)}") }
                o.liquidityUsd?.let { append(" · liquidità ${Notifier.usd0(it)}") }
                if (b != null) {
                    append("\nPrezzo medio on-chain ${Notifier.px(b.execPrice)} · token ${String.format(Locale.ITALY, "%.4f", b.tokens)}")
                    append("\nLordo ${Notifier.usd(b.grossUsd)} − costi ${Notifier.usd(b.costsUsd)} = netto ${Notifier.usd(b.netUsd)}")
                    if (b.ladder.isNotEmpty()) append("\nScala size→netto: " +
                        b.ladder.joinToString(", ") { "${Notifier.usd0(it.first)}→${Notifier.usd0(it.second)}" })
                }
                o.fixed?.let { append("\nCon ${Notifier.usd0(it.notionalUsd)}: netto ${Notifier.usd(it.netUsd)}") }
                if (o.notes.isNotEmpty()) append("\n" + o.notes.joinToString("\n"))
                append("\nTieni premuto per aprire su BaseScan")
            }
            visibility = if (o.address in expanded) View.VISIBLE else View.GONE
        }
        c.addView(details)
        c.setOnClickListener {
            if (details.visibility == View.VISIBLE) {
                details.visibility = View.GONE; expanded -= o.address
            } else {
                details.visibility = View.VISIBLE; expanded += o.address
            }
        }
        c.setOnLongClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://basescan.org/token/${o.address}")))
            true
        }
        return c
    }

    private fun signed(v: Double) = (if (v > 0) "+" else "") + v.toInt()
}

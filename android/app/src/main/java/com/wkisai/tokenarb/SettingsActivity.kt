package com.wkisai.tokenarb

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private data class Field(val key: String, val label: String, val value: String, val numeric: Boolean = true)

    private val edits = mutableMapOf<String, EditText>()
    private lateinit var extended: Switch
    private lateinit var notif: Switch
    private lateinit var buyOnly: Switch

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Store.loadSettings(this)
        val fields = listOf(
            Field("maxNotional", "Capitale massimo per operazione ($)", fmt(s.maxNotional)),
            Field("tradeSize", "Size fissa di confronto ($)", fmt(s.tradeSize)),
            Field("minProfit", "Profitto netto minimo per notifica ($)", fmt(s.minProfit)),
            Field("minBps", "Rendimento netto minimo (bps)", fmt(s.minBps)),
            Field("feePerShare", "Commissione broker per azione ($)", fmt(s.feePerShare)),
            Field("feeBps", "Commissione broker (bps)", fmt(s.feeBps)),
            Field("fixedCost", "Costi fissi per operazione ($)", fmt(s.fixedCost)),
            Field("halfSpreadRegular", "Metà spread azione, sessione regolare (bps)", fmt(s.halfSpreadRegular)),
            Field("halfSpreadExtended", "Metà spread azione, pre/after (bps)", fmt(s.halfSpreadExtended)),
            Field("maxRefAgeMin", "Età massima prezzo di Borsa (min)", fmt(s.maxRefAgeMin)),
            Field("cooldownMin", "Pausa tra notifiche uguali (min)", fmt(s.cooldownMin)),
            Field("closeTolBps", "Posizioni: gap considerato chiuso entro (bps)", fmt(s.closeTolBps)),
            Field("minExitPct", "Posizioni: avvisami se posso vendere almeno il (%)", fmt(s.minExitPct)),
            Field("tickers", "Solo questi ticker (vuoto = tutti, es. AAPL,NVDA)", s.tickers, false),
            Field("exclude", "Escludi ticker", s.exclude, false),
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        for (f in fields) {
            root.addView(TextView(this).apply { text = f.label; setPadding(0, dp(10), 0, 0) })
            val e = EditText(this).apply {
                setText(f.value)
                inputType = if (f.numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            edits[f.key] = e
            root.addView(e)
        }
        extended = Switch(this).apply {
            text = "Copertura possibile anche in pre/after-market"
            isChecked = s.extendedTradable
            setPadding(0, dp(14), 0, 0)
        }
        notif = Switch(this).apply {
            text = "Notifiche attive"
            isChecked = s.notifications
            setPadding(0, dp(14), 0, 0)
        }
        buyOnly = Switch(this).apply {
            text = "Solo arbitraggi in cui conviene comprare il token"
            isChecked = s.buyOnly
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(buyOnly)
        root.addView(extended)
        root.addView(notif)
        root.addView(Button(this).apply {
            text = "Salva"
            setOnClickListener { save() }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        val ed = Store.prefs(this).edit()
        edits.forEach { (k, e) -> ed.putString(k, e.text.toString().trim()) }
        ed.putBoolean("extendedTradable", extended.isChecked)
        ed.putBoolean("notifications", notif.isChecked)
        ed.putBoolean("buyOnly", buyOnly.isChecked)
        ed.apply()
        Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show()
        finish()
    }
}

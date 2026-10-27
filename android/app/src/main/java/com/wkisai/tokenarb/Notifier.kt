package com.wkisai.tokenarb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import kotlin.math.abs

object Notifier {
    private const val CHANNEL = "opportunities"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun directionLabel(d: String) =
        if (d == Sizing.BUY) "COMPRA token / VENDI azione" else "VENDI token / COMPRA azione"

    fun usd(v: Double) = String.format(Locale.ITALY, "$%,.2f", v)
    fun usd0(v: Double) = String.format(Locale.ITALY, "$%,.0f", v)
    fun px(v: Double) = String.format(Locale.ITALY, "%.2f", v)

    /** Prezzi on-chain disponibili: eseguibili (acquisto/vendita) se quotati, altrimenti prezzo medio del pool. */
    fun dexText(o: Opportunity): String {
        val buy = o.dexBuy
        val sell = o.dexSell
        return when {
            buy != null && sell != null -> "DEX ${px(buy)} / ${px(sell)}"
            buy != null -> "DEX acquisto ${px(buy)}"
            sell != null -> "DEX vendita ${px(sell)}"
            o.poolPrice != null -> "pool ${px(o.poolPrice)}"
            else -> "DEX n/d"
        }
    }

    fun body(o: Opportunity): String = buildString {
        val b = o.best
        append("Gap ${o.gapBps.toInt()} bps · ${dexText(o)} vs Borsa ${px(o.fairPrice)}\n")
        if (b != null) {
            append("${directionLabel(b.direction)}\n")
            append("Size ottimale ${usd0(b.notionalUsd)} (${String.format(Locale.ITALY, "%.4f", b.tokens)} ${o.symbol})\n")
            append("Netto ${usd(b.netUsd)} (${b.netBps.toInt()} bps) · costi ${usd(b.costsUsd)}")
        }
        if (o.notes.isNotEmpty()) append("\n" + o.notes.joinToString("; "))
    }

    /** Notifica le opportunità sopra soglia, con cooldown per token e direzione. */
    fun notify(ctx: Context, outcome: ScanOutcome, s: Settings) {
        if (!s.notifications || !canNotify(ctx)) return
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alerts = Store.loadAlerts(ctx)
        val now = System.currentTimeMillis()
        val pi = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        for (o in outcome.opportunities.filter { it.actionable }) {
            val key = "${o.address}|${o.best?.direction}"
            val prev = alerts.optJSONObject(key)
            if (prev != null) {
                val recent = now - prev.optLong("at") < s.cooldownMin * 60_000
                val widened = abs(o.gapBps) - abs(prev.optDouble("gap")) >= 25
                if (recent && !widened) continue
            }
            alerts.put(key, org.json.JSONObject().put("at", now).put("gap", o.gapBps))
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("⚡ ${o.symbol}: netto ${usd(o.best?.netUsd ?: 0.0)}")
                .setContentText(o.best?.let { directionLabel(it.direction) } ?: "")
                .setStyle(Notification.BigTextStyle().bigText(body(o)))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(o.address.hashCode(), n)
        }
        Store.saveAlerts(ctx, alerts)
    }
}

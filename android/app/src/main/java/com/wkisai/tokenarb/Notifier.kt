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
import kotlin.math.max
import kotlin.math.roundToInt

object Notifier {
    private const val CHANNEL = "opportunities"
    private const val CHANNEL_POS = "positions"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (nm.getNotificationChannel(CHANNEL_POS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_POS, "Posizioni da chiudere", NotificationManager.IMPORTANCE_HIGH)
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
    fun signedUsd(v: Double) = (if (v >= 0) "+" else "−") + usd(abs(v))
    fun px(v: Double) = String.format(Locale.ITALY, "%.2f", v)
    fun qty(v: Double) = String.format(Locale.ITALY, "%.4f", v)

    /** Prezzi on-chain disponibili: eseguibili se quotati, altrimenti prezzo della pool. */
    fun dexText(o: Opportunity): String {
        val buy = o.dexBuy
        val sell = o.dexSell
        return when {
            buy != null && sell != null -> "DEX ${px(buy)} / ${px(sell)}"
            buy != null -> "acquisto on-chain ${px(buy)}"
            sell != null -> "vendita on-chain ${px(sell)}"
            o.poolPrice != null -> "pool ${px(o.poolPrice)}"
            else -> "DEX n/d"
        }
    }

    fun body(o: Opportunity): String = buildString {
        val b = o.best
        append("Gap ${o.gapBps.toInt()} bps · ${dexText(o)} vs Borsa ${px(o.fairPrice)}\n")
        if (b != null) {
            append("${directionLabel(b.direction)}\n")
            append("Size ottimale ${usd0(b.notionalUsd)} (${qty(b.tokens)} ${o.symbol})\n")
            append("Netto ${usd(b.netUsd)} (${b.netBps.toInt()} bps) · costi ${usd(b.costsUsd)}")
        }
        if (o.notes.isNotEmpty()) append("\n" + o.notes.joinToString("; "))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Notifica le opportunità sopra soglia, con cooldown per token e direzione. */
    fun notify(ctx: Context, outcome: ScanOutcome, s: Settings) {
        if (!s.notifications || !canNotify(ctx)) return
        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alerts = Store.loadAlerts(ctx)
        val now = System.currentTimeMillis()
        val pi = openApp(ctx)

        for (o in outcome.opportunities.filter { it.actionable }) {
            if (s.buyOnly && o.best?.direction != Sizing.BUY) continue
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

    /**
     * Esiti dei controlli sulle posizioni: avvisa quando si può vendere almeno la quota minima
     * chiudendo il gap. Nuovo avviso se la quota vendibile sale, promemoria dopo il cooldown.
     */
    fun notifyPositions(ctx: Context, positions: List<Position>, fresh: List<PositionStatus>, s: Settings) {
        val prev = Store.loadStatuses(ctx)
        val now = System.currentTimeMillis()
        val minF = max(0.1, s.minExitPct / 100.0) - 1e-9
        val post = s.notifications && canNotify(ctx)
        if (post) ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val out = mutableListOf<PositionStatus>()
        for (st in fresh) {
            val p = positions.firstOrNull { it.id == st.id } ?: continue
            val old = prev[st.id]
            var nf = old?.notifiedFraction ?: 0.0
            var na = old?.notifiedAt ?: 0L
            if (st.fraction < minF) {
                nf = 0.0   // gap di nuovo aperto: il prossimo segnale verrà notificato
            } else if (st.fraction > nf + 1e-9 || now - na >= s.cooldownMin * 60_000) {
                if (post) {
                    val pct = (st.fraction * 100).roundToInt()
                    val text = buildString {
                        append("Gap chiuso: vendi ${qty(st.sellTokens ?: 0.0)} ${p.symbol} ")
                        append("a ~${px(st.avgSellPrice ?: 0.0)} (Borsa ${px(st.fair ?: 0.0)})\n")
                        append("Incasso ~${usd(st.proceedsUsd ?: 0.0)} · risultato su questa parte ")
                        append(signedUsd(st.pnlUsd ?: 0.0) + "\n")
                        append("Posizione: ${usd0(p.usd)} a ${px(p.entryPrice)}")
                        st.note?.let { append("\n$it") }
                    }
                    val n = Notification.Builder(ctx, CHANNEL_POS)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle("💰 ${p.symbol}: puoi vendere il $pct%")
                        .setContentText("Incasso ~${usd0(st.proceedsUsd ?: 0.0)} · ${signedUsd(st.pnlUsd ?: 0.0)}")
                        .setStyle(Notification.BigTextStyle().bigText(text))
                        .setContentIntent(openApp(ctx))
                        .setAutoCancel(true)
                        .build()
                    nm.notify(("pos:" + p.id).hashCode(), n)
                }
                nf = st.fraction
                na = now
            }
            out += st.copy(notifiedFraction = nf, notifiedAt = na)
        }
        Store.mergeStatuses(ctx, out)
    }
}

package com.wkisai.tokenarb

import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Esegue il motore di scansione dell'app contro le API reali (Coinbase, Robinhood, DexScreener,
 * GeckoTerminal, Yahoo, KyberSwap) e scrive un rapporto in build/live-report.md.
 * Gira solo con TOKENARB_LIVE=1 (workflow "Android live scan test").
 */
class LiveScanTest {
    private val report = StringBuilder()

    private fun section(title: String, body: String) {
        report.append("## ").append(title).append('\n').append(body.trim()).append("\n\n")
        println("== $title\n$body\n")
    }

    private fun f(v: Double, d: Int = 2) = String.format(Locale.ITALY, "%.${d}f", v)

    @Test
    fun liveScan() {
        assumeTrue(System.getenv("TOKENARB_LIVE") == "1")
        val s = Settings()
        val st = Scanner.State(null, 0L, ConcurrentHashMap())
        // due posizioni di prova: NVDA su Base (Coinbase) e TSLA su Robinhood Chain
        val positions = listOf(
            Position("t1", "0xb20000000000000000000078ee7ce2fE4908108C", "NVDAc", "NVDA",
                500.0, 200.0, 2.5, 0L, Chain.BASE),
            Position("t2", "0x322F0929c4625eD5bAd873c95208D54E1c003b2d", "TSLA", "TSLA",
                500.0, 380.0, 500.0 / 380.0, 0L, Chain.ROBINHOOD),
        )

        val runs = mutableListOf<String>()
        var last: ScanOutcome? = null
        var statuses: List<PositionStatus> = emptyList()
        for (round in 1..2) {
            val t0 = System.currentTimeMillis()
            val timeline = Collections.synchronizedList(mutableListOf<String>())
            var lastProgress = ""
            var firstList = -1.0
            val out = Scanner.runExclusive(
                s, st, positions,
                onUpdate = { o ->
                    val sec = (System.currentTimeMillis() - t0) / 1000.0
                    if (firstList < 0 && o.opportunities.isNotEmpty()) firstList = sec
                    if (o.progress != lastProgress) {
                        timeline += "${f(sec, 1)} s · ${o.progress} · ${o.opportunities.size} token a schermo"
                        lastProgress = o.progress
                    }
                },
                onPositions = { statuses = it },
            )
            assertNotNull(out)
            last = out
            val total = (System.currentTimeMillis() - t0) / 1000.0
            runs += "Scansione $round: ${f(total, 1)} s · primi risultati a schermo dopo ${f(firstList, 1)} s · ${out!!.feedInfo}\n" +
                timeline.joinToString("\n") { "  $it" }
        }
        val out = last!!
        section("Tempi", runs.joinToString("\n"))

        val byChain = out.opportunities.groupBy { it.chain }
        section("Copertura", buildString {
            append("Token negli elenchi: ${out.tokensFound}\n")
            for ((chain, list) in byChain) {
                val verified = list.count { it.verified }
                val sized = list.count { it.best != null }
                val profitable = list.count { (it.best?.netUsd ?: 0.0) > 0 }
                val liq = list.mapNotNull { it.liquidityUsd }
                append("${chain.label}: ${list.size} con pool · verificati $verified · con size $sized · " +
                    "in profitto $profitable · liquidità <$1k ${liq.count { it < 1e3 }}, <$10k ${liq.count { it < 1e4 }}\n")
            }
        })

        val buys = out.opportunities.filter { (it.best?.netUsd ?: 0.0) > 0 && it.best?.direction == Sizing.BUY }
        section("Da comprare (netto positivo, per rendimento)", buys.take(30).joinToString("\n") { o ->
            val b = o.best!!
            "${o.label.padEnd(11)} ${o.chain.label.padEnd(10)} sconto ${(-o.gapBps).toInt()} bps · " +
                "liq $${o.liquidityUsd?.toInt()} · size $${b.notionalUsd.toInt()} · netto $${f(b.netUsd)} " +
                "(${b.netBps.toInt()} bps)${if (o.actionable) " ⚡" else ""} · ${o.dex}"
        }.ifEmpty { "nessuno in questo momento" })

        val discounts = out.opportunities.filter { it.gapBps < 0 && (it.best?.netUsd ?: -1.0) <= 0 }
            .sortedBy { it.gapBps }.take(25)
        section("Sconti scartati dopo la verifica", discounts.joinToString("\n") { o ->
            "${o.label.padEnd(11)} sconto ${(-o.gapBps).toInt()} bps · liq $${o.liquidityUsd?.toInt()} · " +
                "acquisto on-chain ${o.dexBuy?.let { f(it) } ?: "-"} vs Borsa ${f(o.fairPrice)} · " +
                (o.notes.lastOrNull() ?: "")
        }.ifEmpty { "nessuno" })

        val pending = out.opportunities.count { !it.verified }
        val weird = out.opportunities.filter { abs(it.gapBps) > Scanner.MAX_PLAUSIBLE_GAP_BPS }
        section("Controlli", buildString {
            append("Ancora in verifica a fine scansione: $pending (deve essere 0)\n")
            append("Gap oltre il 50% (dati sospetti): ${weird.size}: ")
            append(weird.take(15).joinToString(", ") { "${it.label} ${it.gapBps.toInt()} bps" })
        })
        section("Note della scansione", out.errors.joinToString("\n").ifEmpty { "nessuna" })
        section("Posizioni di prova", statuses.joinToString("\n") { p ->
            "${p.id}: vendibile ${(p.fraction * 100).toInt()}% · pool ${p.poolPrice?.let { f(it) }} · " +
                "Borsa ${p.fair?.let { f(it) }} · ${p.note ?: ""}"
        }.ifEmpty { "nessun esito" })

        File("build").mkdirs()
        File("build/live-report.md").writeText(report.toString())
    }
}

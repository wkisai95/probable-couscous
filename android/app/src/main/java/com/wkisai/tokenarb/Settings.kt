package com.wkisai.tokenarb

/** Impostazioni modificabili dall'app. */
data class Settings(
    val maxNotional: Double = 25_000.0,
    val tradeSize: Double = 1_000.0,
    val minProfit: Double = 2.0,       // i token poco liquidi danno profitti piccoli ma rendimenti alti
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
    val coinbase: Boolean = true,      // stock token Coinbase su Base
    val robinhood: Boolean = true,     // stock token Robinhood su Robinhood Chain
    val tickers: String = "",          // vuoto = tutti
    val exclude: String = "",
) {
    fun tickerSet() = tickers.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    fun excludeSet() = exclude.split(",", " ").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()

    fun chains(): List<Chain> = buildList {
        if (coinbase) add(Chain.BASE)
        if (robinhood) add(Chain.ROBINHOOD)
    }
}

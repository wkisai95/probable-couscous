package com.wkisai.tokenarb

/** Chain supportate: emittente dei token, nomi usati dalle API e stablecoin di riferimento. */
enum class Chain(
    val label: String,          // nome mostrato nell'app
    val issuer: String,         // emittente degli stock token
    val dexscreener: String,    // id chain su DexScreener
    val gecko: String,          // id rete su GeckoTerminal
    val kyber: String,          // id chain nell'API di KyberSwap
    val stable: String,         // stablecoin usato per comprare e vendere
    val stableSymbol: String,
    val stableDecimals: Int,
    val explorerToken: String,  // prefisso della pagina token sull'explorer
) {
    BASE(
        "Base", "Coinbase", "base", "base", "base",
        "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", "USDC", 6,
        "https://basescan.org/token/",
    ),
    ROBINHOOD(
        "Robinhood", "Robinhood", "robinhood", "robinhood", "robinhood",
        "0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168", "USDG", 6,
        "https://robinhoodchain.blockscout.com/token/",
    );

    companion object {
        fun of(name: String?): Chain = entries.firstOrNull { it.name == name } ?: BASE
    }
}

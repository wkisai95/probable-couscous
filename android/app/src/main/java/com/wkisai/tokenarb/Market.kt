package com.wkisai.tokenarb

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Sessioni di mercato USA, ora di New York. */
object Market {
    private val NY: ZoneId = ZoneId.of("America/New_York")
    private val HOLIDAYS = setOf(
        "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25",
        "2026-06-19", "2026-07-03", "2026-09-07", "2026-11-26", "2026-12-25",
        "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31",
        "2027-06-18", "2027-07-05", "2027-09-06", "2027-11-25", "2027-12-24",
    )

    fun session(now: Instant = Instant.now()): String {
        val ny = now.atZone(NY)
        if (ny.dayOfWeek == DayOfWeek.SATURDAY || ny.dayOfWeek == DayOfWeek.SUNDAY ||
            ny.toLocalDate().toString() in HOLIDAYS) return "closed"
        val t = ny.toLocalTime()
        return when {
            t >= LocalTime.of(9, 30) && t < LocalTime.of(16, 0) -> "regular"
            t >= LocalTime.of(4, 0) && t < LocalTime.of(9, 30) -> "pre"
            t >= LocalTime.of(16, 0) && t < LocalTime.of(20, 0) -> "post"
            else -> "closed"
        }
    }

    fun label(s: String) = when (s) {
        "regular" -> "aperta"
        "pre" -> "pre-market"
        "post" -> "after-hours"
        else -> "chiusa"
    }
}

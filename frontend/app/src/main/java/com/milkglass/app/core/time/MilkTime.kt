package com.milkglass.app.core.time

import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.data.Translator
import com.milkglass.app.core.data.WEEK_MS
import com.milkglass.app.core.model.Anime
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

fun jsToFixed(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

class MilkTime(
    private val tr: Translator,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
    private val formatLocale: Locale = Locale("id", "ID"),
) {
    val localOffsetMs: Long = zone.getOffset(nowProvider()).toLong()

    val timezoneLabel: String = runCatching {
        zone.getDisplayName(zone.inDaylightTime(Date(nowProvider())), TimeZone.SHORT, formatLocale)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: tr.t("common.localTime")

    fun now(): Long = nowProvider()

    private fun offsetAt(ts: Long): Long = zone.getOffset(ts).toLong()
    private val zoneId = zone.toZoneId()

    fun formatClock(ts: Long): String {
        val minutes = Math.floorDiv(ts + offsetAt(ts), 60_000L)
        val mm = Math.floorMod(minutes, 60L)
        val hh = Math.floorMod(Math.floorDiv(minutes, 60L), 24L)
        return hh.toString().padStart(2, '0') + ":" + mm.toString().padStart(2, '0')
    }

    fun startOfDay(offsetDays: Int = 0): Long =
        java.time.LocalDate.ofEpochDay(Math.floorDiv(now() + offsetAt(now()), DAY_MS))
            .plusDays(offsetDays.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()

    fun startOfToday(): Long = startOfDay(0)

    fun weekdayOf(ts: Long): Int = Math.floorMod(Math.floorDiv(ts + offsetAt(ts), DAY_MS) + 4, 7L).toInt()

    fun dayOfMonthOf(ts: Long): Int =
        java.time.LocalDate.ofEpochDay(Math.floorDiv(ts + offsetAt(ts), DAY_MS)).dayOfMonth

    fun monthIndexOf(ts: Long): Int =
        java.time.LocalDate.ofEpochDay(Math.floorDiv(ts + offsetAt(ts), DAY_MS)).monthValue - 1

    fun formatCountdown(ms: Long): String {
        if (ms <= 0) return tr.t("time.alreadyAired")
        val m = ceil(ms / 60_000.0).toLong()
        if (m < 60) return tr.t("time.minutes", "m" to m)
        val h = m / 60
        if (h < 24) return tr.t("time.hoursMinutes", "h" to h, "value" to (m % 60))
        val d = h / 24
        return tr.t("time.daysHours", "d" to d, "value" to (h % 24))
    }

    fun formatTimeAgo(ts: Long): String {
        val m = Math.floorDiv(now() - ts, 60_000L)
        if (m < 1) return tr.t("time.justNow")
        if (m < 60) return tr.t("time.minutesAgo", "m" to m)
        val h = m / 60
        if (h < 24) return tr.t("time.hoursAgo", "h" to h)
        val d = h / 24
        return if (d == 1L) tr.t("time.yesterday") else tr.t("time.daysAgo", "d" to d)
    }

    fun formatDuration(seconds: Double): String {
        val s = max(0.0, floor(seconds)).toLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val x = s % 60
        val head = if (h != 0L) "$h:" + m.toString().padStart(2, '0') else m.toString()
        return head + ":" + x.toString().padStart(2, '0')
    }

    fun formatCompactCount(n: Int): String =
        if (n >= 1000) tr.t("format.thousandSuffix", "replace" to jsToFixed(n / 1000.0, 1).replace('.', ','))
        else n.toString()

    fun formatRating(r: Double): String = jsToFixed(r, 1).replace('.', ',')

    fun formatRupiah(n: Long): String = "Rp" + NumberFormat.getIntegerInstance(formatLocale).format(n)

    fun durationSeconds(a: Anime): Int = a.episodeMinutes * 60

    fun episodeTitle(a: Anime, ep: Int): String {

        return tr.t("episode.label", "n" to ep)
    }

    fun releasedEpisodeCount(a: Anime): Int {
        val air = a.nextAirAt ?: return a.totalEpisodes
        val extra = if (now() >= air) 1 + floor((now() - air).toDouble() / WEEK_MS).toInt() else 0
        return min(a.totalEpisodes, a.releasedEpisodes + extra)
    }

    fun isOngoing(a: Anime): Boolean = releasedEpisodeCount(a) < a.totalEpisodes && a.nextAirAt != null

    fun nextReleaseAt(a: Anime): Long? {
        if (!isOngoing(a)) return null
        val air = a.nextAirAt!!
        val k = if (now() >= air) 1 + floor((now() - air).toDouble() / WEEK_MS).toLong() else 0L
        return air + k * WEEK_MS
    }
}

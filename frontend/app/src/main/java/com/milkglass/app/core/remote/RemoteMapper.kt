package com.milkglass.app.core.remote

import androidx.compose.runtime.Immutable

import com.milkglass.app.core.app.GENRES
import com.milkglass.app.core.model.Anime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Immutable
data class CatalogEntry(val anime: Anime, val posterUrl: String?, val malId: Int?)

object RemoteMapper {
    private const val STATUS_RELEASING = 1
    private const val WIB_OFFSET_MS = 7 * 3_600_000L
    private const val DAY_MS = 86_400_000L
    private val digits = Regex("""\d+""")
    private val year = Regex("""\d{4}""")

    fun toEntry(r: RemoteAnime): CatalogEntry =
        CatalogEntry(toAnime(r), r.posterUrl?.takeIf { it.startsWith("https://") }, r.malId)

    fun toAnime(r: RemoteAnime): Anime {
        val ongoing = r.status == STATUS_RELEASING
        val released = when {
            ongoing && r.nextAiringEp > 0 -> r.nextAiringEp - 1
            !ongoing && r.episodesTotal > 0 -> r.episodesTotal
            else -> 0
        }

        val total = when {
            r.episodesTotal > 0 -> maxOf(r.episodesTotal, released)
            ongoing -> released + 1
            else -> released
        }

        val hasAir = r.nextAiringAt > 0
        val (colorFrom, colorTo) = colorPair(r.id)
        return Anime(
            id = r.id,
            title = r.titleAlt?.takeIf { it.isNotBlank() } ?: r.title,
            altTitle = r.title,
            genres = mapGenres(r.genres),
            colorFrom = colorFrom,
            colorTo = colorTo,
            totalEpisodes = total,
            releasedEpisodes = released,
            airWeekday = if (hasAir) wibWeekday(r.nextAiringAt) else null,
            airTime = if (hasAir) wibClock(r.nextAiringAt) else "",
            rating = r.score ?: 0.0,
            voteCount = r.popularity,
            studio = r.studio?.takeIf { it.isNotBlank() && it != "-" } ?: "",
            year = year.find(r.releaseSeason.orEmpty())?.value?.toIntOrNull() ?: 0,
            ageRating = "",
            episodeMinutes = digits.find(r.duration.orEmpty())?.value?.toIntOrNull() ?: 24,
            openingRange = listOf(0, 0),
            synopsis = r.synopsis.orEmpty(),
            similarIds = emptyList(),
            nextAirAt = if (hasAir) r.nextAiringAt * 1000 else null,
        )
    }

    fun mapGenres(raw: List<String>): List<String> =
        raw.map { it.trim().lowercase(Locale.ROOT).replace(' ', '-') }.filter { it in GENRES }.distinct()

    fun wibWeekday(epochSec: Long): Int = (Math.floorDiv(epochSec * 1000 + WIB_OFFSET_MS, DAY_MS) + 4).mod(7L).toInt()

    fun wibClock(epochSec: Long): String {
        val minuteOfDay = Math.floorMod(Math.floorDiv(epochSec * 1000 + WIB_OFFSET_MS, 60_000L), 24 * 60L).toInt()
        return "%02d:%02d".format(Locale.ROOT, minuteOfDay / 60, minuteOfDay % 60)
    }

    fun colorPair(id: Int): Pair<String, String> {
        val hue = (id.toLong() * 137L).mod(360L).toDouble()
        return hslToHex(hue, 0.70, 0.50) to hslToHex((hue + 40.0) % 360.0, 0.70, 0.35)
    }

    private fun hslToHex(h: Double, s: Double, l: Double): String {
        val c = (1.0 - abs(2.0 * l - 1.0)) * s
        val hp = h / 60.0
        val x = c * (1.0 - abs(hp % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hp < 1.0 -> Triple(c, x, 0.0)
            hp < 2.0 -> Triple(x, c, 0.0)
            hp < 3.0 -> Triple(0.0, c, x)
            hp < 4.0 -> Triple(0.0, x, c)
            hp < 5.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2.0
        fun ch(v: Double) = ((v + m) * 255.0).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(Locale.ROOT, ch(r1), ch(g1), ch(b1))
    }
}

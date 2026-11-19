package com.milkglass.app.core.data

import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.model.Conversation
import com.milkglass.app.core.model.Message
import com.milkglass.app.core.model.SeedConversation
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

const val HOUR_MS = 3_600_000L
const val DAY_MS = 24 * HOUR_MS
const val WEEK_MS = 7 * DAY_MS
const val WIB_OFFSET_MS = 7 * HOUR_MS
const val MINUTE_MS = 60_000L

object SeedData {

    fun withNextAirAt(catalog: List<Anime>, bootMs: Long): List<Anime> = catalog.map { a ->
        val weekday = a.airWeekday
        if (weekday == null || a.releasedEpisodes >= a.totalEpisodes) return@map a.copy(nextAirAt = null)
        val wibNow = java.time.Instant.ofEpochMilli(bootMs + WIB_OFFSET_MS).atZone(ZoneOffset.UTC)
        val date: LocalDate = wibNow.toLocalDate()
        val hh = a.airTime.substring(0, 2).toInt()
        val mm = a.airTime.substring(3).toInt()
        var t = LocalDateTime.of(date, LocalTime.of(hh, mm)).toInstant(ZoneOffset.UTC).toEpochMilli() - WIB_OFFSET_MS
        var found: Long? = null
        for (i in 0 until 8) {
            val dow = java.time.Instant.ofEpochMilli(t + WIB_OFFSET_MS).atZone(ZoneOffset.UTC).dayOfWeek.value % 7
            if (t > bootMs && dow == weekday) { found = t; break }
            t += DAY_MS
        }
        a.copy(nextAirAt = found)
    }

    fun conversations(raw: List<SeedConversation>, nowMs: Long): List<Conversation> = raw.map { c ->
        Conversation(
            id = c.id, kind = c.kind, name = c.name, ownerId = c.ownerId, memberIds = c.memberIds,
            unreadCount = c.unreadCount, peerId = c.peerId,
            messages = c.messages.mapIndexed { j, m ->
                Message(id = c.id + "." + j, authorId = m.authorId, text = m.text, sentAt = nowMs - m.minutesAgo * MINUTE_MS)
            },
        )
    }
}

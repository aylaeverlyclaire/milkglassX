package com.milkglass.app.core.data

import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.model.Conversation
import com.milkglass.app.core.model.HistoryEntry
import com.milkglass.app.core.model.Message
import com.milkglass.app.core.storage.PersistedState

private val COMMENT_AUTHOR_IDS = listOf("rin", "dito", "mika", "bayu", "sora", "nana")

fun SeedData.thread(anime: Anime, ep: Int, nowMs: Long, templates: List<String>): List<Message> {
    val aid = anime.id
    val n = 4 + ((aid + ep) % 3)
    return (0 until n).map { i ->
        val k = (aid * 5 + ep * 3 + i * 2) % templates.size
        Message(
            id = "t$aid.$ep.$i",
            authorId = COMMENT_AUTHOR_IDS[(aid + ep + i) % COMMENT_AUTHOR_IDS.size],
            text = templates[k].replace("{title}", anime.romaji),
            sentAt = nowMs - (n - i) * 37L * MINUTE_MS - ep * MINUTE_MS,
        )
    }
}

fun SeedData.initialState(tr: Translator, seedConversations: List<Conversation>, nowMs: Long): PersistedState {
    fun seedProgress(id: Int, n: Int, partial: Double? = null): Pair<String, Map<String, Double>> {
        val m = linkedMapOf<String, Double>()
        for (e in 1..n) m[e.toString()] = 1440.0
        if (partial != null) m[(n + 1).toString()] = partial
        return id.toString() to m
    }
    val progress = linkedMapOf(
        seedProgress(0, 23, 860.0), seedProgress(1, 3), seedProgress(4, 24),
        seedProgress(6, 7, 410.0), seedProgress(9, 12),
    )
    val lastWatched = linkedMapOf(
        "0" to nowMs - 6 * MINUTE_MS, "6" to nowMs - 22 * HOUR_MS, "1" to nowMs - 3 * DAY_MS,
        "4" to nowMs - 5 * DAY_MS, "9" to nowMs - 9 * DAY_MS,
    )
    val history = listOf(
        HistoryEntry(0, 24, nowMs - 6 * MINUTE_MS), HistoryEntry(6, 8, nowMs - 22 * HOUR_MS),
        HistoryEntry(6, 7, nowMs - 23 * HOUR_MS), HistoryEntry(1, 3, nowMs - 3 * DAY_MS),
        HistoryEntry(4, 24, nowMs - 5 * DAY_MS), HistoryEntry(9, 12, nowMs - 9 * DAY_MS),
    )
    return PersistedState(
        progress = progress,
        lastWatched = lastWatched,
        history = history,
        watchlist = listOf(2, 6),
        friendIds = listOf("dito", "nana"),
        recentSearches = listOf("Kaelan", "Mecha"),
        conversations = seedConversations,
    )
}

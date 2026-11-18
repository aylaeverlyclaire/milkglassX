package com.milkglass.app.core.storage

import com.milkglass.app.core.model.Conversation
import com.milkglass.app.core.model.HistoryEntry
import com.milkglass.app.core.model.Settings
import com.milkglass.app.core.model.Subscription
import kotlinx.serialization.Serializable

@Serializable
data class PersistedState(
    val displayName: String? = null,
    val handle: String? = null,
    val profilePhoto: String? = null,
    val groupPhotos: Map<String, String> = emptyMap(),
    val progress: Map<String, Map<String, Double>> = emptyMap(),
    val history: List<HistoryEntry> = emptyList(),
    val lastWatched: Map<String, Long> = emptyMap(),
    val watchlist: List<Int> = emptyList(),
    val blockedUserIds: List<String> = emptyList(),
    val settings: Settings = Settings(),
    val subscription: Subscription? = null,
    val resumePositions: Map<String, Map<String, Double>> = emptyMap(),
    val recentSearches: List<String> = emptyList(),
    val reportedMessages: Map<String, Double> = emptyMap(),
    val collectionTab: String = "watchlist",
    val socialTab: String = "public",
    val conversations: List<Conversation> = emptyList(),
    val threads: Map<String, Conversation> = emptyMap(),
    val nextGroupId: Int = 1,
    val isAuthenticated: Int = 0,
    val accessToken: String? = null,
    val deletionScheduledAt: Long? = null,
    val earlyAccessNotified: Map<String, Int> = emptyMap(),
    val reminders: List<String> = emptyList(),
    val friendIds: List<String> = emptyList(),
    val downloads: Map<String, Int> = emptyMap(),
)

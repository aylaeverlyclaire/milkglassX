package com.milkglass.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Anime(
    val id: Int,
    val title: String,
    val altTitle: String,
    val genres: List<String>,
    val colorFrom: String,
    val colorTo: String,
    val totalEpisodes: Int,
    val releasedEpisodes: Int,
    val airWeekday: Int? = null,
    val airTime: String = "",
    val rating: Double,
    val voteCount: Int,
    val studio: String,
    val year: Int,
    val ageRating: String,
    val episodeMinutes: Int,
    val openingRange: List<Int>,
    val synopsis: String,
    val similarIds: List<Int>,

    val nextAirAt: Long? = null,
) {

    val romaji: String get() = altTitle.substringBefore(" · ")
}

@Serializable
data class User(
    val name: String,
    val color: String,
    val role: String? = null,
    val isPublic: Int? = null,
    val watchedIds: List<Int>? = null,
    val handle: String? = null,
)

@Serializable
data class SharedEpisode(val animeId: Int? = null, val episode: Int? = null, val position: Double? = null)

@Serializable
data class Message(
    val id: String? = null,
    val authorId: String? = null,
    val text: String? = null,
    val sentAt: Long? = null,
    val isSystem: Int? = null,
    val replyToId: String? = null,
    val isDeleted: Boolean? = null,
    val deletedBy: String? = null,
    val deletedByHandle: String? = null,
    val deletedByRole: String? = null,
    val sharedEpisode: SharedEpisode? = null,
)

@Serializable
data class RateState(
    val sentAt: List<Long> = emptyList(),
    val strikes: Int = 0,
    val cleanMessages: Int = 0,
    val lastText: String? = null,
    val cooldownEndsAt: Long? = null,
    val cooldownSeconds: Int? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val kind: String,
    val name: String? = null,
    val ownerId: String? = null,
    val memberIds: List<String> = emptyList(),
    val unreadCount: Int = 0,
    val messages: List<Message> = emptyList(),
    val peerId: String? = null,
    val pinnedMessageId: String? = null,
    val mutedMembers: Map<String, Long>? = null,
    val adminIds: List<String>? = null,
    val inviteCode: String? = null,
    val ownNickname: String? = null,
    val firstUnreadIndex: Int? = null,
    val rateState: RateState? = null,
    val animeId: Int? = null,
    val episode: Int? = null,
)

@Serializable
data class Settings(
    val theme: String = "system",
    val quality: String = "Auto",
    val autoplayNext: Int = 1,
    val episodeReminders: Int = 1,
    val autoSkipOpeningEnding: Int = 0,
    val wifiOnlyDownloads: Int = 1,
    val watchlistAudience: String = "onlyMe",
    val directMessageAudience: String = "everyone",
)

@Serializable
data class Subscription(
    val active: Int = 0,
    val plan: String? = null,
    val pricePaid: Int? = null,
    val renewsAt: Long? = null,
    val cancelled: Boolean = false,
)

@Serializable
data class HistoryEntry(val id: Int, val episode: Int, val watchedAt: Long)

@Serializable
data class FeatureFlags(
    val social: Boolean = false,
    val premium: Boolean = false,
    val ads: Boolean = false,
    val share: Boolean = false,
    val like: Boolean = false,
    val favorite: Boolean = false,
    val comments: Boolean = false,
    val autoSkip: Boolean = false,
    val reminders: Boolean = false,
    val watchParty: Boolean = false,
    val roleBadges: Boolean = false,
)

@Serializable
data class SeedMessage(val id: String? = null, val authorId: String, val text: String, val minutesAgo: Int)

@Serializable
data class SeedConversation(
    val id: String,
    val kind: String,
    val name: String? = null,
    val ownerId: String? = null,
    val memberIds: List<String> = emptyList(),
    val unreadCount: Int = 0,
    val messages: List<SeedMessage> = emptyList(),
    val peerId: String? = null,
)

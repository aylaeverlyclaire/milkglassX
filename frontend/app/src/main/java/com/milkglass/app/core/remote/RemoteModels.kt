package com.milkglass.app.core.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val RemoteJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
data class RemoteAnime(
    val id: Int,
    @SerialName("mal_id") val malId: Int? = null,
    val title: String,
    @SerialName("title_alt") val titleAlt: String? = null,
    val score: Double? = null,
    val type: String? = null,
    
    val status: Int = 2,
    @SerialName("schedule_day") val scheduleDay: Int = 0,
    val duration: String? = null,
    val studio: String? = null,
    @SerialName("release_season") val releaseSeason: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val popularity: Int = 0,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    val genres: List<String> = emptyList(),
    
    @SerialName("episodes_total") val episodesTotal: Int = 0,
    
    @SerialName("next_airing_at") val nextAiringAt: Long = 0L,
    @SerialName("next_airing_ep") val nextAiringEp: Int = 0,
    
    val synopsis: String? = null,
    val playable: Boolean = true,
)

@Serializable
data class SyncMeta(
    @SerialName("synced_at") val syncedAt: Long? = null,
    @SerialName("full_synced_at") val fullSyncedAt: Long? = null,
)

@Serializable
data class HomeData(
    val ongoing: List<RemoteAnime> = emptyList(),
    val popular: List<RemoteAnime> = emptyList(),
    val tamat: List<RemoteAnime> = emptyList(),
    val extras: List<RemoteAnime> = emptyList(),
)

@Serializable
data class HomeResponse(
    val status: String,
    val data: HomeData? = null,
    val meta: SyncMeta = SyncMeta(),
    val message: String? = null,
)

@Serializable
data class BrowseResponse(
    val status: String,
    val data: List<RemoteAnime> = emptyList(),
    val message: String? = null,
)

@Serializable
data class DetailResponse(
    val status: String,
    val data: RemoteAnime? = null,
    val message: String? = null,
)

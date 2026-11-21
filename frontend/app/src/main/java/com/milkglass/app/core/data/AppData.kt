package com.milkglass.app.core.data

import android.content.Context
import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.model.FeatureFlags
import com.milkglass.app.core.model.SeedConversation
import com.milkglass.app.core.model.User
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

val AssetJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = false
}

class AppData(
    val locale: LocaleData,
    val catalogRaw: List<Anime>,
    val users: Map<String, User>,
    val seedConversationsRaw: List<SeedConversation>,
    val features: FeatureFlags,
) {
    companion object {

        fun load(context: Context): AppData = load { path ->
            if (path == "anime_catalog.json" || path == "seed_conversations.json") "[]"
            else context.assets.open("data/$path").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        fun load(read: (String) -> String): AppData = AppData(
            locale = AssetJson.decodeFromString(LocaleData.serializer(), read("locale_id.json")),
            catalogRaw = AssetJson.decodeFromString(ListSerializer(Anime.serializer()), read("anime_catalog.json")),
            users = AssetJson.decodeFromString(
                MapSerializer(String.serializer(), User.serializer()), read("users.json"),
            ),
            seedConversationsRaw = AssetJson.decodeFromString(
                ListSerializer(SeedConversation.serializer()), read("seed_conversations.json"),
            ),
            features = AssetJson.decodeFromString(FeatureFlags.serializer(), read("features.json")),
        )
    }
}

val VIBES = listOf("relax", "tense", "hype", "touching")

val GENRE_VIBES: Map<String, Set<String>> = mapOf(
    "slice-of-life" to setOf("relax", "touching"),
    "comedy" to setOf("relax"),
    "music" to setOf("relax", "touching"),
    "fantasy" to setOf("relax", "touching"),
    "drama" to setOf("touching"),
    "mystery" to setOf("tense"),
    "supernatural" to setOf("tense"),
    "action" to setOf("hype", "tense"),
    "adventure" to setOf("hype"),
    "sports" to setOf("hype"),
    "mecha" to setOf("hype"),
    "romance" to setOf("touching", "relax"),
    "sci-fi" to setOf("hype", "tense"),
    "horror" to setOf("tense"),
    "psychological" to setOf("tense"),
    "thriller" to setOf("tense"),
    "mahou-shoujo" to setOf("relax", "hype"),
)

fun vibesOf(genres: List<String>): Set<String> = genres.flatMapTo(linkedSetOf()) { GENRE_VIBES[it].orEmpty() }

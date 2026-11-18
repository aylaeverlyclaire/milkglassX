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

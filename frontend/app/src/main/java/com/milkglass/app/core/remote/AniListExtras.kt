package com.milkglass.app.core.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

data class AniListExtras(val recommendedIds: List<Int>, val airingAtMs: Map<Int, Long>)

interface AniListExtrasSource {
    
    suspend fun load(anilistId: Int): AniListExtras?
}

private const val EXTRAS_QUERY = "query(\$id:Int){Media(id:\$id,type:ANIME){" +
    "recommendations(sort:RATING_DESC,perPage:15){nodes{rating mediaRecommendation{id}}}" +
    "airingSchedule(notYetAired:true,perPage:50){nodes{episode airingAt}}}}"

class AniListExtrasHttpSource(private val timeoutMs: Int = 10_000) : AniListExtrasSource {
    override suspend fun load(anilistId: Int): AniListExtras? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                val body = "{\"query\":\"" + EXTRAS_QUERY.replace("\\", "\\\\").replace("\"", "\\\"") +
                    "\",\"variables\":{\"id\":$anilistId}}"
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 404) return@withContext AniListExtras(emptyList(), emptyMap())
                if (code !in 200..299) return@withContext null
                parseAniListExtras(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

internal fun parseAniListExtras(json: String): AniListExtras? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val media = ((root["data"] as? JsonObject)?.get("Media")) as? JsonObject ?: return AniListExtras(emptyList(), emptyMap())
    fun nodes(key: String): List<JsonObject> =
        (((media[key] as? JsonObject)?.get("nodes")) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    
    val rec = nodes("recommendations")
        .filter { ((it["rating"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) > 0.0 }
        .mapNotNull { ((it["mediaRecommendation"] as? JsonObject)?.get("id") as? JsonPrimitive)?.intOrNull }
        .distinct()
    val air = HashMap<Int, Long>()
    for (n in nodes("airingSchedule")) {
        val ep = (n["episode"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: continue
        val at = (n["airingAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: continue
        air[ep] = at * 1000
    }
    return AniListExtras(rec, air)
}

package com.milkglass.app.core.remote

import id.my.milkglass.EngineHttpTextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

data class EpisodeMeta(val title: String?, val image: String?)

interface EpisodeMetaSource {
    suspend fun load(anilistId: Int): Map<Int, EpisodeMeta>?
}

class AniZipMetaSource(
    private val timeoutMs: Int = 10_000,
    private val httpClient: EngineHttpTextClient? = null,
) : EpisodeMetaSource {
    override suspend fun load(anilistId: Int): Map<Int, EpisodeMeta>? = httpGetJson(
        "https://api.ani.zip/mappings?anilist_id=$anilistId",
        timeoutMs,
        notFound = emptyMap(),
        httpClient = httpClient,
        parse = ::parseAniZipEpisodes,
    )
}

internal suspend fun <T : Any> httpGetJson(
    url: String,
    timeoutMs: Int,
    notFound: T,
    httpClient: EngineHttpTextClient? = null,
    parse: (String) -> T?,
): T? =
    withContext(Dispatchers.IO) {
        try {
            if (httpClient != null) {
                val resp = httpClient.get(url)
                if (resp.code == 404) return@withContext notFound
                if (resp.code !in 200..299) return@withContext null
                return@withContext parse(resp.body)
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code == 404) return@withContext notFound
                if (code !in 200..299) return@withContext null
                parse(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

private val GENERIC_TITLE = Regex("^(episode|eps?\\.?)\\s*\\d+$", RegexOption.IGNORE_CASE)

private fun pickTitle(el: JsonElement?): String? {
    val raw = when (el) {
        is JsonPrimitive -> el.contentOrNull
        is JsonObject -> listOf("en", "x-jat", "ja").firstNotNullOfOrNull { k ->
            (el[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        else -> null
    }?.trim()
    return raw?.takeIf { it.isNotEmpty() && !GENERIC_TITLE.matches(it) }
}

internal fun parseAniZipEpisodes(json: String): Map<Int, EpisodeMeta>? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val eps = root["episodes"] as? JsonObject ?: return emptyMap()
    val out = HashMap<Int, EpisodeMeta>()
    for ((key, value) in eps) {
        val n = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
        val obj = value as? JsonObject ?: continue
        val raw = (obj["image"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val image = when {
            raw.startsWith("https://") -> raw
            raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
            else -> null
        }
        val title = pickTitle(obj["title"])
        if (title != null || image != null) out[n] = EpisodeMeta(title, image)
    }
    return out
}

package com.milkglass.app.core.remote

import id.my.milkglass.EngineHttpTextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gambar lanskap 16:9 (banner) per anime, untuk kartu lebar. Poster portrait 2:3 TIDAK boleh dipaksa
 * mengisi kartu 16:9 karena terpotong parah.
 */
interface BannerSource {
    /** Peta id AniList -> URL banner. Id tanpa banner tidak ada di peta. `null` = gagal (boleh dicoba lagi). */
    suspend fun load(ids: List<Int>): Map<Int, String>?
}

private const val BANNER_QUERY =
    "query(\$ids:[Int]){Page(perPage:50){media(id_in:\$ids,type:ANIME){id bannerImage}}}"

class AniListBannerSource(
    private val timeoutMs: Int = 10_000,
    private val httpClient: EngineHttpTextClient? = null,
) : BannerSource {
    override suspend fun load(ids: List<Int>): Map<Int, String>? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        try {
            val body = "{\"query\":\"" + BANNER_QUERY.replace("\\", "\\\\").replace("\"", "\\\"") +
                "\",\"variables\":{\"ids\":[" + ids.take(50).joinToString(",") + "]}}"
            if (httpClient != null) {
                val resp = httpClient.postJson("https://graphql.anilist.co", body)
                return@withContext if (resp.code in 200..299) parseAniListBanners(resp.body) else null
            }
            val conn = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) null
                else parseAniListBanners(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
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

internal fun parseAniListBanners(json: String): Map<Int, String>? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val media = (((root["data"] as? JsonObject)?.get("Page") as? JsonObject)?.get("media")) as? JsonArray ?: return null
    val out = HashMap<Int, String>()
    for (el in media) {
        val o = el as? JsonObject ?: continue
        val id = (o["id"] as? JsonPrimitive)?.intOrNull ?: continue
        val raw = (o["bannerImage"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val url = when {
            raw.startsWith("https://") -> raw
            raw.startsWith("http://") -> "https://" + raw.removePrefix("http://")
            else -> null
        }
        if (url != null) out[id] = url
    }
    return out
}

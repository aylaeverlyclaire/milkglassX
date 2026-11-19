package com.milkglass.app.core.remote

import id.my.milkglass.EngineHttpTextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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

/**
 * Fanart 16:9 resolusi penuh (±1920x1080) dari ani.zip — ini yang cocok untuk kartu 16:9.
 * Banner AniList (±1900x400, rasio 4,75:1) hanya cadangan: dipotong ke 16:9 ia jadi irisan sempit yang pecah.
 */
class AniZipFanartSource(
    private val timeoutMs: Int = 10_000,
    private val httpClient: EngineHttpTextClient? = null,
    private val concurrency: Int = 4,
) : BannerSource {
    override suspend fun load(ids: List<Int>): Map<Int, String>? = kotlinx.coroutines.coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(concurrency)
        val results = ids.map { id ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    id to httpGetJson(
                        "https://api.ani.zip/mappings?anilist_id=$id",
                        timeoutMs, notFound = "", httpClient = httpClient, parse = ::parseAniZipFanart,
                    )
                }
            }
        }.awaitAll()
        if (results.isNotEmpty() && results.all { it.second == null }) null
        else results.mapNotNull { (id, url) -> url?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
    }
}

/** URL fanart pertama dari `images` ani.zip; "" bila tidak ada, null bila JSON rusak. Selalu https. */
internal fun parseAniZipFanart(json: String): String? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    for (el in (root["images"] as? JsonArray).orEmpty()) {
        val o = el as? JsonObject ?: continue
        if ((o["coverType"] as? JsonPrimitive)?.contentOrNull?.equals("Fanart", ignoreCase = true) != true) continue
        val raw = (o["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        when {
            raw.startsWith("https://") -> return raw
            raw.startsWith("http://") -> return "https://" + raw.removePrefix("http://")
        }
    }
    return ""
}

/** Coba [primary]; id yang tidak punya gambar di sana dicoba di [fallback]. */
class ChainedBannerSource(private val primary: BannerSource, private val fallback: BannerSource) : BannerSource {
    override suspend fun load(ids: List<Int>): Map<Int, String>? {
        val first = try { primary.load(ids) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val missing = ids.filter { first == null || it !in first }
        if (missing.isEmpty()) return first
        val second = fallback.load(missing)
        return when {
            first == null -> second
            second == null -> first
            else -> first + second
        }
    }
}

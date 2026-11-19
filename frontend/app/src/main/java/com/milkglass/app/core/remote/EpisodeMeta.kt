package com.milkglass.app.core.remote

import id.my.milkglass.EngineHttpTextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

data class EpisodeMeta(val title: String?, val image: String?)

interface EpisodeMetaSource {
    suspend fun load(anilistId: Int): Map<Int, EpisodeMeta>?

    /** [expected] = jumlah episode yang sudah rilis; dipakai rantai sumber untuk tahu judul mana yang masih bolong. */
    suspend fun loadFor(anilistId: Int, expected: Int): Map<Int, EpisodeMeta>? = load(anilistId)
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
    }
    return cleanTitle(raw)
}

/** Rapikan judul dari sumber luar: backtick/kutip miring jadi apostrof, spasi ganda dibuang, "Episode N" polos dianggap kosong. */
internal fun cleanTitle(raw: String?): String? {
    val t = raw?.replace('`', '\'')?.replace('\u2019', '\'')?.replace('\u2018', '\'')
        ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    return t.takeIf { it.isNotEmpty() && !GENERIC_TITLE.matches(it) }
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

/** Gabung per episode: judul/gambar dari [primary] menang, yang kosong diisi dari [extra]. */
internal fun mergeEpisodeMeta(primary: Map<Int, EpisodeMeta>, extra: Map<Int, EpisodeMeta>): Map<Int, EpisodeMeta> {
    if (extra.isEmpty()) return primary
    val out = HashMap<Int, EpisodeMeta>(primary)
    for ((n, e) in extra) {
        val cur = out[n]
        out[n] = if (cur == null) e else EpisodeMeta(cur.title ?: e.title, cur.image ?: e.image)
    }
    return out
}

/** Masih ada episode rilis yang judulnya kosong? [expected] = 0 berarti jumlah tidak diketahui. */
internal fun hasTitleGaps(m: Map<Int, EpisodeMeta>, expected: Int): Boolean {
    if (m.isEmpty()) return true
    if (expected > 0) return (1..expected).any { m[it]?.title == null }
    return m.values.any { it.title == null }
}

/** Coba [primary] dulu; kalau gagal atau judulnya bolong, lengkapi dari [fallback]. */
class ChainedEpisodeMetaSource(
    private val primary: EpisodeMetaSource,
    private val fallback: EpisodeMetaSource,
) : EpisodeMetaSource {
    override suspend fun load(anilistId: Int): Map<Int, EpisodeMeta>? = loadFor(anilistId, 0)

    override suspend fun loadFor(anilistId: Int, expected: Int): Map<Int, EpisodeMeta>? {
        val first = primary.loadFor(anilistId, expected)
        if (first != null && !hasTitleGaps(first, expected)) return first
        val second = try {
            fallback.loadFor(anilistId, expected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return when {
            first == null -> second
            second == null -> first
            else -> mergeEpisodeMeta(first, second)
        }
    }
}

private val STREAMING_TITLE = Regex("""^\s*(?:episode|eps?\.?)\s*(\d+)\s*[-–—:.]\s*(.+)$""", RegexOption.IGNORE_CASE)

private const val ANILIST_EPISODES_QUERY = "query(\$id:Int){Media(id:\$id,type:ANIME){idMal streamingEpisodes{title thumbnail}}}"

internal data class StreamingParse(val malId: Int?, val episodes: Map<Int, EpisodeMeta>)

/** AniList `streamingEpisodes`: judulnya berbentuk "Episode 3 - Judul", jadi nomor dan judul dipisah di sini. */
internal fun parseAniListStreaming(json: String): StreamingParse? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val media = ((root["data"] as? JsonObject)?.get("Media")) as? JsonObject ?: return StreamingParse(null, emptyMap())
    val mal = (media["idMal"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
    val out = HashMap<Int, EpisodeMeta>()
    for (el in (media["streamingEpisodes"] as? JsonArray).orEmpty()) {
        val o = el as? JsonObject ?: continue
        val raw = (o["title"] as? JsonPrimitive)?.contentOrNull ?: continue
        val m = STREAMING_TITLE.matchEntire(raw) ?: continue
        val n = m.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
        val title = cleanTitle(m.groupValues[2])
        val thumb = (o["thumbnail"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.startsWith("https://") }
        if (title != null || thumb != null) out.putIfAbsent(n, EpisodeMeta(title, thumb))
    }
    return StreamingParse(mal, out)
}

internal data class JikanPage(val titles: Map<Int, String>, val hasNext: Boolean)

/** Jikan (MyAnimeList) `/anime/{id}/episodes`: `mal_id` di sini adalah nomor episode. */
internal fun parseJikanEpisodes(json: String): JikanPage? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val data = root["data"] as? JsonArray ?: return null
    val out = HashMap<Int, String>()
    for (el in data) {
        val o = el as? JsonObject ?: continue
        val n = (o["mal_id"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: continue
        val t = cleanTitle((o["title"] as? JsonPrimitive)?.contentOrNull) ?: continue
        out[n] = t
    }
    val next = ((root["pagination"] as? JsonObject)?.get("has_next_page") as? JsonPrimitive)?.booleanOrNull ?: false
    return JikanPage(out, next)
}

/**
 * Cadangan judul episode: AniList (streamingEpisodes, sekalian ambil idMal), lalu Jikan/MyAnimeList
 * untuk nomor yang masih kosong.
 */
class AniListJikanMetaSource(
    private val timeoutMs: Int = 10_000,
    private val httpClient: EngineHttpTextClient? = null,
    private val maxJikanPages: Int = 4,
) : EpisodeMetaSource {
    override suspend fun load(anilistId: Int): Map<Int, EpisodeMeta>? = loadFor(anilistId, 0)

    override suspend fun loadFor(anilistId: Int, expected: Int): Map<Int, EpisodeMeta>? {
        val streaming = postJsonText(anilistId)?.let(::parseAniListStreaming)
        var result: Map<Int, EpisodeMeta> = streaming?.episodes.orEmpty()
        val mal = streaming?.malId
        if (mal != null && hasTitleGaps(result, expected)) {
            val titles = HashMap<Int, EpisodeMeta>()
            var page = 1
            while (page <= maxJikanPages) {
                val p = httpGetJson(
                    "https://api.jikan.moe/v4/anime/$mal/episodes?page=$page",
                    timeoutMs, notFound = JikanPage(emptyMap(), false), httpClient = httpClient, parse = ::parseJikanEpisodes,
                )
                if (p == null) break
                p.titles.forEach { (n, t) -> titles[n] = EpisodeMeta(t, null) }
                if (!p.hasNext) break
                page++
                delay(450) // Jikan: maks ~3 request/detik
            }
            result = mergeEpisodeMeta(result, titles)
        }
        return if (streaming == null && result.isEmpty()) null else result
    }

    private suspend fun postJsonText(anilistId: Int): String? = withContext(Dispatchers.IO) {
        try {
            val body = "{\"query\":\"" + ANILIST_EPISODES_QUERY.replace("\\", "\\\\").replace("\"", "\\\"") +
                "\",\"variables\":{\"id\":$anilistId}}"
            if (httpClient != null) {
                val resp = httpClient.postJson("https://graphql.anilist.co", body)
                return@withContext if (resp.code in 200..299) resp.body else null
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
                else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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

package com.milkglass.app.core.remote

import androidx.compose.runtime.Immutable

import id.my.milkglass.EngineHttpTextClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class SkipWindow(val start: Double, val end: Double) {
    fun contains(p: Double) = p >= start && p < end
}

@Immutable
data class SkipTimes(val opening: SkipWindow?, val ending: SkipWindow?) {
    companion object { val NONE = SkipTimes(null, null) }
}

interface SkipSource {
    suspend fun load(malId: Int, episode: Int): SkipTimes?
}

class AniSkipSource(
    private val timeoutMs: Int = 10_000,
    private val httpClient: EngineHttpTextClient? = null,
) : SkipSource {
    private val cache = ConcurrentHashMap<Long, Pair<Long, SkipTimes>>()

    override suspend fun load(malId: Int, episode: Int): SkipTimes? {
        if (malId <= 0 || episode <= 0) return SkipTimes.NONE
        val key = (malId.toLong() shl 20) or (episode.toLong() and 0xFFFFFL)
        val now = System.currentTimeMillis()
        cache[key]?.let { (exp, value) ->
            if (exp > now) return value
            cache.remove(key)
        }
        val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode?types=op&types=ed&episodeLength=0"
        val fetched = httpGetJson(url, timeoutMs, notFound = SkipTimes.NONE, httpClient = httpClient, parse = ::parseAniSkip)
        if (fetched != null) {
            if (cache.size >= 256) cache.clear()
            cache[key] = (System.currentTimeMillis() + CACHE_TTL_MS) to fetched
        }
        return fetched
    }

    companion object {
        private const val CACHE_TTL_MS = 60L * 60L * 1000L
    }
}

internal fun parseAniSkip(json: String): SkipTimes? {
    val root = runCatching { RemoteJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
    val results = root["results"] as? JsonArray ?: return SkipTimes.NONE
    var op: SkipWindow? = null
    var ed: SkipWindow? = null
    for (item in results) {
        val o = item as? JsonObject ?: continue
        val iv = o["interval"] as? JsonObject ?: continue
        val s = (iv["startTime"] as? JsonPrimitive)?.doubleOrNull ?: continue
        val e = (iv["endTime"] as? JsonPrimitive)?.doubleOrNull ?: continue
        if (s < 0.0 || e <= s) continue
        when ((o["skipType"] as? JsonPrimitive)?.contentOrNull) {
            "op" -> if (op == null) op = SkipWindow(s, e)
            "ed" -> if (ed == null) ed = SkipWindow(s, e)
        }
    }
    return SkipTimes(op, ed)
}

package id.my.milkglass.source

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SourceSlugCache(context: Context?) {
    private val prefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences("milkglass_source_slug_cache", Context.MODE_PRIVATE)

    private val memory = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(
        val seriesUrl: String,
        val cachedAtMs: Long
    )

    private fun makeKey(source: SourcePriority, animeTitle: String): String {
        val norm = animeTitle.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return "${source.id}::$norm"
    }

    fun get(source: SourcePriority, animeTitle: String, maxAgeMs: Long = DEFAULT_TTL_MS): String? {
        val key = makeKey(source, animeTitle)
        val now = System.currentTimeMillis()
        memory[key]?.let { entry ->
            if (now - entry.cachedAtMs <= maxAgeMs) return entry.seriesUrl
            memory.remove(key, entry)
        }
        val raw = prefs?.getString(key, null) ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val url = obj.optString("url")
        val ts = obj.optLong("ts", 0L)
        if (url.startsWith("http") && now - ts <= maxAgeMs) {
            if (memory.size >= MAX_MEMORY_ENTRIES) memory.clear()
            memory[key] = CacheEntry(url, ts)
            return url
        }
        invalidate(source, animeTitle)
        return null
    }

    fun put(source: SourcePriority, animeTitle: String, seriesUrl: String) {
        if (!seriesUrl.startsWith("http")) return
        val key = makeKey(source, animeTitle)
        val now = System.currentTimeMillis()
        val prev = memory[key]
        if (prev != null && prev.seriesUrl == seriesUrl && (now - prev.cachedAtMs) < WRITE_COALESCE_MS) {
            return
        }
        if (memory.size >= MAX_MEMORY_ENTRIES) memory.clear()
        memory[key] = CacheEntry(seriesUrl, now)
        val json = JSONObject()
            .put("url", seriesUrl)
            .put("ts", now)
            .toString()
        prefs?.edit()?.putString(key, json)?.apply()
    }

    fun invalidate(source: SourcePriority, animeTitle: String) {
        val key = makeKey(source, animeTitle)
        memory.remove(key)
        prefs?.edit()?.remove(key)?.apply()
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_MEMORY_ENTRIES = 512
        private const val WRITE_COALESCE_MS = 5L * 60L * 1000L
    }
}

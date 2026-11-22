package id.my.milkglass.source

import android.content.Context
import android.content.SharedPreferences
import id.my.milkglass.net.IsolatedHttpClients
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class IndexedAnimeEntry(
    val source: SourcePriority,
    val title: String,
    val normalizedTitle: String,
    val tokens: Set<String>,
    val url: String
)

class SourceAvailabilityIndex(
    context: Context?,
    private val clients: IsolatedHttpClients = IsolatedHttpClients(context),
    private val slugCache: SourceSlugCache = SourceSlugCache(context),
    private val baseUrlProvider: (SourcePriority) -> String = { it.defaultBaseUrl }
) {
    private val prefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences("milkglass_availability_index", Context.MODE_PRIVATE)

    @Volatile
    private var entries: List<IndexedAnimeEntry> = emptyList()

    @Volatile
    private var tokenLookup: Map<String, List<IndexedAnimeEntry>> = emptyMap()

    @Volatile
    private var sourceTokenLookup: Map<SourcePriority, Map<String, List<IndexedAnimeEntry>>> = emptyMap()

    @Volatile
    private var lastSyncedAtMs: Long = 0L
    @Volatile private var syncedSourceCount: Int = 0

    private val diskLoaded = java.util.concurrent.CountDownLatch(1)

    init {
        if (prefs != null) {
            Thread {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                try {
                    loadFromDisk()
                } finally {
                    diskLoaded.countDown()
                }
            }.start()
        } else {
            diskLoaded.countDown()
        }
    }

    /** Menunggu pemuatan indeks dari disk selesai (dibatasi waktu). Jangan dipanggil dari thread UI tanpa batas kecil. */
    fun awaitDiskLoad(timeoutMs: Long = 10_000L) {
        runCatching { diskLoaded.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    val isReady: Boolean
        get() = entries.size >= MIN_VALID_ENTRIES

    fun indexedCount(): Int = entries.size

    fun loadEntriesForTest(items: List<IndexedAnimeEntry>) {
        applyEntries(items)
    }

    fun refreshIfNeeded(force: Boolean = false): Int {
        awaitDiskLoad()
        val now = System.currentTimeMillis()
        val activeTtlMs = if (syncedSourceCount >= SourcePriority.entries.size) INDEX_TTL_MS else PARTIAL_INDEX_RETRY_MS
        if (!force && isReady && (now - lastSyncedAtMs) < activeTtlMs) {
            return entries.size
        }

        val executor = Executors.newFixedThreadPool(4) { r ->
            Thread {
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }.apply { isDaemon = true; name = "milkglass-index-fetch" }
        }
        val fetchedBySource = linkedMapOf<SourcePriority, List<IndexedAnimeEntry>>()
        try {
            val tasks = listOf(
                SourcePriority.SAMEHADAKU to executor.submit<List<IndexedAnimeEntry>> { runCatching { fetchSamehadakuIndex() }.getOrDefault(emptyList()) },
                SourcePriority.OTAKUDESU to executor.submit<List<IndexedAnimeEntry>> { runCatching { fetchOtakudesuIndex() }.getOrDefault(emptyList()) }
            )
            for ((src, fut) in tasks) {
                val list = runCatching { fut.get(20, TimeUnit.SECONDS) }.getOrNull().orEmpty()
                if (list.isNotEmpty()) {
                    fetchedBySource[src] = list
                }
            }
        } finally {
            executor.shutdownNow()
        }

        if (fetchedBySource.isNotEmpty()) {
            val existingBySource = entries.groupBy { it.source }
            val merged = mutableListOf<IndexedAnimeEntry>()
            for (src in SourcePriority.entries) {
                val fresh = fetchedBySource[src]
                if (!fresh.isNullOrEmpty()) {
                    merged.addAll(fresh)
                } else {
                    existingBySource[src]?.let { merged.addAll(it) }
                }
            }
            if (merged.size >= MIN_VALID_ENTRIES) {
                applyEntries(merged)
                syncedSourceCount = merged.map { it.source }.toSet().size
                lastSyncedAtMs = now
                saveToDisk(merged, now)
            }
        }
        return entries.size
    }

    fun lookupBestUrl(source: SourcePriority, animeTitle: String): String? {
        if (!isReady) return null
        val cleaned = animeTitle.trim()
        if (cleaned.length < 2) return null
        val perSource = sourceTokenLookup[source] ?: return null
        val parsed = TitleMatcher.parse(cleaned)
        if (parsed.tokens.isEmpty()) return null

        val bucket = parsed.tokens.asSequence()
            .mapNotNull { perSource[it] }
            .minByOrNull { it.size }
            ?: return null

        var bestEntry: IndexedAnimeEntry? = null
        var bestScore = 0.0
        for (entry in bucket) {
            val sc = TitleMatcher.score(cleaned, entry.title)
            if (sc > bestScore) {
                bestScore = sc
                bestEntry = entry
                if (bestScore >= 0.98) break
            }
        }
        if (bestEntry != null && bestScore >= 0.48) {
            slugCache.put(source, cleaned, bestEntry.url)
            return bestEntry.url
        }
        return null
    }

    fun isAvailable(candidateTitles: List<String>): Boolean {
        if (!isReady) return true
        val cleaned = candidateTitles.map { it.trim() }.filter { it.length >= 2 }
        if (cleaned.isEmpty()) return false

        var matchedAny = false
        for (title in cleaned) {
            val parsed = TitleMatcher.parse(title)
            if (parsed.tokens.isEmpty()) continue

            val bucket = parsed.tokens.asSequence()
                .mapNotNull { tokenLookup[it] }
                .minByOrNull { it.size }
                ?: continue

            val bestBySource = HashMap<SourcePriority, Pair<IndexedAnimeEntry, Double>>()
            for (entry in bucket) {
                val sc = TitleMatcher.score(title, entry.title)
                if (sc >= 0.45) {
                    val prev = bestBySource[entry.source]
                    if (prev == null || sc > prev.second) {
                        bestBySource[entry.source] = entry to sc
                    }
                }
            }
            if (bestBySource.isNotEmpty()) {
                bestBySource.forEach { (src, pair) ->
                    slugCache.put(src, title, pair.first.url)
                }
                matchedAny = true
            }
        }
        return matchedAny
    }

    private fun applyEntries(list: List<IndexedAnimeEntry>) {
        val deduped = list.distinctBy { "${it.source.id}::${it.normalizedTitle}" }
            .sortedBy { it.source.rank }
        val map = HashMap<String, MutableList<IndexedAnimeEntry>>(deduped.size * 2)
        val perSrc = HashMap<SourcePriority, HashMap<String, MutableList<IndexedAnimeEntry>>>()
        for (item in deduped) {
            val srcMap = perSrc.getOrPut(item.source) { HashMap() }
            for (tok in item.tokens) {
                map.getOrPut(tok) { mutableListOf() }.add(item)
                srcMap.getOrPut(tok) { mutableListOf() }.add(item)
            }
        }
        entries = deduped
        tokenLookup = map
        sourceTokenLookup = perSrc
    }

    private fun fetchOtakudesuIndex(): List<IndexedAnimeEntry> {
        val base = baseUrlProvider(SourcePriority.OTAKUDESU).trimEnd('/')
        val html = fetchHtml("$base/anime-list/", "$base/") ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select("a.hodebgst[href], .jdlbar ul li a[href]").mapNotNull { a ->
            buildEntry(SourcePriority.OTAKUDESU, a.text(), a.absUrl("href"))
        }
    }

    private fun fetchSamehadakuIndex(): List<IndexedAnimeEntry> {
        val base = baseUrlProvider(SourcePriority.SAMEHADAKU).trimEnd('/')
        val html = fetchHtml("$base/daftar-anime-2/?list", "$base/") ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select(".listpst a[href], .animposx a[href], .jdlbar a[href]").mapNotNull { a ->
            val title = a.attr("title").ifBlank { a.text() }
            buildEntry(SourcePriority.SAMEHADAKU, title, a.absUrl("href"))
        }
    }

    private fun fetchHtml(url: String, referer: String?): String? {
        val req = Request.Builder().url(url).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        val client = clients.sourceClient
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun buildEntry(source: SourcePriority, rawTitle: String, url: String): IndexedAnimeEntry? {
        val cleanTitle = rawTitle.replace(WHITESPACE, " ").trim()
        if (cleanTitle.length < 2 || !url.startsWith("http")) return null
        val tokens = TitleMatcher.tokenize(cleanTitle)
        if (tokens.isEmpty()) return null
        return IndexedAnimeEntry(
            source = source,
            title = cleanTitle,
            normalizedTitle = normalizeTitle(cleanTitle),
            tokens = tokens,
            url = url
        )
    }

    private fun normalizeTitle(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(NON_ALNUM, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    private fun saveToDisk(list: List<IndexedAnimeEntry>, ts: Long) {
        val p = prefs ?: return
        runCatching {
            val arr = JSONArray()
            for (item in list.take(8000)) {
                arr.put(
                    JSONObject()
                        .put("s", item.source.name)
                        .put("t", item.title)
                        .put("u", item.url)
                )
            }
            p.edit()
                .putLong("ts", ts)
                .putString("items", arr.toString())
                .apply()
        }
    }

    private fun loadFromDisk() {
        val p = prefs ?: return
        val ts = p.getLong("ts", 0L)
        val raw = p.getString("items", null) ?: return
        if (System.currentTimeMillis() - ts > INDEX_TTL_MS) return
        runCatching {
            val arr = JSONArray(raw)
            val list = mutableListOf<IndexedAnimeEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val src = runCatching { SourcePriority.valueOf(obj.optString("s")) }.getOrNull() ?: continue
                buildEntry(src, obj.optString("t"), obj.optString("u"))?.let { list.add(it) }
            }
            if (list.isNotEmpty()) {
                applyEntries(list)
                syncedSourceCount = list.map { it.source }.toSet().size
                lastSyncedAtMs = ts
            }
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val NON_ALNUM = Regex("[^a-z0-9\\s]+")
        const val INDEX_TTL_MS = 12L * 60L * 60L * 1000L
        const val PARTIAL_INDEX_RETRY_MS = 10L * 60L * 1000L
        const val MIN_VALID_ENTRIES = 15
    }
}

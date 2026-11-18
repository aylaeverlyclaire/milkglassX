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
    private val slugCache: SourceSlugCache = SourceSlugCache(context)
) {
    private val prefs: SharedPreferences? = context?.applicationContext
        ?.getSharedPreferences("milkglass_availability_index", Context.MODE_PRIVATE)

    @Volatile
    private var entries: List<IndexedAnimeEntry> = emptyList()

    @Volatile
    private var tokenLookup: Map<String, List<IndexedAnimeEntry>> = emptyMap()

    @Volatile
    private var lastSyncedAtMs: Long = 0L

    init {
        loadFromDisk()
    }

    val isReady: Boolean
        get() = entries.size >= MIN_VALID_ENTRIES

    fun indexedCount(): Int = entries.size

    fun loadEntriesForTest(items: List<IndexedAnimeEntry>) {
        applyEntries(items)
    }

    fun refreshIfNeeded(force: Boolean = false): Int {
        val now = System.currentTimeMillis()
        if (!force && isReady && (now - lastSyncedAtMs) < INDEX_TTL_MS) {
            return entries.size
        }

        val executor = Executors.newFixedThreadPool(4)
        val collected = mutableListOf<IndexedAnimeEntry>()
        val futures = listOf(
            executor.submit<List<IndexedAnimeEntry>> { runCatching { fetchOtakudesuIndex() }.getOrDefault(emptyList()) },
            executor.submit<List<IndexedAnimeEntry>> { runCatching { fetchSamehadakuIndex() }.getOrDefault(emptyList()) },
            executor.submit<List<IndexedAnimeEntry>> { runCatching { fetchOploverzIndex() }.getOrDefault(emptyList()) },
            executor.submit<List<IndexedAnimeEntry>> { runCatching { fetchKusonimeIndex() }.getOrDefault(emptyList()) }
        )
        for (f in futures) {
            runCatching { f.get(20, TimeUnit.SECONDS) }.getOrNull()?.let { collected.addAll(it) }
        }
        executor.shutdownNow()

        if (collected.size >= MIN_VALID_ENTRIES) {
            applyEntries(collected)
            lastSyncedAtMs = now
            saveToDisk(collected, now)
        }
        return entries.size
    }

    fun isAvailable(candidateTitles: List<String>): Boolean {
        if (!isReady) return true
        val cleaned = candidateTitles.map { it.trim() }.filter { it.length >= 2 }
        if (cleaned.isEmpty()) return false

        for (title in cleaned) {
            val norm = normalizeTitle(title)
            val qTokens = TitleMatcher.tokenize(title)
            if (qTokens.isEmpty()) continue

            val bucket = qTokens.asSequence()
                .mapNotNull { tokenLookup[it] }
                .minByOrNull { it.size }
                ?: continue

            var bestEntry: IndexedAnimeEntry? = null
            var bestScore = 0.0
            for (entry in bucket) {
                if (entry.normalizedTitle == norm ||
                    (norm.length >= 6 && (entry.normalizedTitle.contains(norm) || norm.contains(entry.normalizedTitle)))
                ) {
                    bestEntry = entry
                    bestScore = 1.0
                    break
                }
                val inter = qTokens.intersect(entry.tokens).size.toDouble()
                val union = qTokens.union(entry.tokens).size.toDouble()
                val sc = if (union > 0.0) inter / union else 0.0
                if (sc > bestScore) {
                    bestScore = sc
                    bestEntry = entry
                }
            }
            if (bestEntry != null && bestScore >= 0.45) {
                slugCache.put(bestEntry.source, title, bestEntry.url)
                return true
            }
        }
        return false
    }

    private fun applyEntries(list: List<IndexedAnimeEntry>) {
        val deduped = list.distinctBy { "${it.source.id}::${it.normalizedTitle}" }
            .sortedBy { it.source.rank }
        val map = HashMap<String, MutableList<IndexedAnimeEntry>>(deduped.size * 2)
        for (item in deduped) {
            for (tok in item.tokens) {
                map.getOrPut(tok) { mutableListOf() }.add(item)
            }
        }
        entries = deduped
        tokenLookup = map
    }

    private fun fetchOtakudesuIndex(): List<IndexedAnimeEntry> {
        val base = SourcePriority.OTAKUDESU.defaultBaseUrl
        val html = fetchHtml("$base/anime-list/", "$base/") ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select("a.hodebgst[href], .jdlbar ul li a[href]").mapNotNull { a ->
            buildEntry(SourcePriority.OTAKUDESU, a.text(), a.absUrl("href"))
        }
    }

    private fun fetchSamehadakuIndex(): List<IndexedAnimeEntry> {
        val base = SourcePriority.SAMEHADAKU.defaultBaseUrl
        val html = fetchHtml("$base/daftar-anime-2/?list", "$base/") ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select(".listpst a[href], .animposx a[href], .jdlbar a[href]").mapNotNull { a ->
            val title = a.attr("title").ifBlank { a.text() }
            buildEntry(SourcePriority.SAMEHADAKU, title, a.absUrl("href"))
        }
    }

    private fun fetchOploverzIndex(): List<IndexedAnimeEntry> {
        val base = SourcePriority.OPLOVERZ.defaultBaseUrl
        val html = fetchHtml("$base/anime/list-mode/", "$base/")
        val htmlList = if (!html.isNullOrBlank()) {
            val doc = Jsoup.parse(html, base)
            doc.select(".soralist a[href], .blix a[href]").mapNotNull { a ->
                buildEntry(SourcePriority.OPLOVERZ, a.text(), a.absUrl("href"))
            }
        } else {
            emptyList()
        }
        if (htmlList.isNotEmpty()) return htmlList

        val apiJson = fetchHtml("https://backapi.oploverz.ac/api/series?page=1&pageSize=250", null)
            ?: return emptyList()
        val arr = runCatching { JSONObject(apiJson).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val out = mutableListOf<IndexedAnimeEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("title")
            val slug = obj.optString("slug")
            if (title.isNotBlank() && slug.isNotBlank()) {
                buildEntry(SourcePriority.OPLOVERZ, title, "$base/series/$slug")?.let { out.add(it) }
            }
        }
        return out
    }

    private fun fetchKusonimeIndex(): List<IndexedAnimeEntry> {
        val base = SourcePriority.KUSONIME.defaultBaseUrl
        val html = fetchHtml("$base/list-anime-batch-sub-indo/", "$base/") ?: return emptyList()
        val doc = Jsoup.parse(html, base)
        return doc.select("#abtext a[href], .jdlbar a[href]").mapNotNull { a ->
            buildEntry(SourcePriority.KUSONIME, a.text(), a.absUrl("href"))
        }
    }

    private fun fetchHtml(url: String, referer: String?): String? {
        val req = Request.Builder().url(url).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        val client = if (url.contains("backapi.oploverz.ac")) clients.mediaAndApiClient else clients.sourceClient
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()
    }

    private fun buildEntry(source: SourcePriority, rawTitle: String, url: String): IndexedAnimeEntry? {
        val cleanTitle = rawTitle.replace(Regex("\\s+"), " ").trim()
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
            .replace(Regex("[^a-z0-9\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun saveToDisk(list: List<IndexedAnimeEntry>, ts: Long) {
        val p = prefs ?: return
        runCatching {
            val arr = JSONArray()
            for (item in list.take(4000)) {
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
                lastSyncedAtMs = ts
            }
        }
    }

    companion object {
        const val INDEX_TTL_MS = 12L * 60L * 60L * 1000L
        const val MIN_VALID_ENTRIES = 15
    }
}

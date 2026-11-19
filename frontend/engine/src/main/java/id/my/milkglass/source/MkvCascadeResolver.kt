package id.my.milkglass.source

import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.extractor.LinkUnshortener
import id.my.milkglass.extractor.MkvHosterExtractorRegistry
import id.my.milkglass.net.CloudflareChallengeException
import id.my.milkglass.net.IsolatedHttpClients
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class SourcePriority(val rank: Int, val id: String, val defaultBaseUrl: String) {
    OTAKUDESU(1, "otakudesu", "https://otakudesu.blog"),
    SAMEHADAKU(2, "samehadaku", "https://v2.samehadaku.how"),
    OPLOVERZ(3, "oploverz", "https://oploverz.ch"),
    KUSONIME(4, "kusonime", "https://kusonime.com")
}

enum class MediaContainer(val rank: Int, val label: String) {
    MKV(0, "MKV"),
    MP4(1, "MP4"),
    HLS(2, "HLS"),
    UNKNOWN(3, "AUTO");

    companion object {
        fun detect(headerText: String, rowText: String, url: String = ""): MediaContainer {
            val combined = "${headerText.lowercase(Locale.ROOT)} ${rowText.lowercase(Locale.ROOT)} ${url.lowercase(Locale.ROOT)}"
            return when {
                combined.contains(".m3u8") || combined.contains("hls") -> HLS
                combined.contains("mkv") || combined.contains("x265") || combined.contains("hevc") -> MKV
                combined.contains("mp4") -> MP4
                else -> UNKNOWN
            }
        }
    }
}

enum class MkvQuality(val label: String, val height: Int) {
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360);

    companion object {
        val ALL_DESC: List<MkvQuality> = listOf(P1080, P720, P480, P360)

        fun fromText(text: String): MkvQuality? {
            val low = text.lowercase(Locale.ROOT).trim()
            return when {
                low.contains("1080") || low.contains("fullhd") || low == "fhd" -> P1080
                low.contains("720") || low == "hd" -> P720
                low.contains("480") || low == "mini" || low == "sd" -> P480
                low.contains("360") -> P360
                else -> null
            }
        }
    }
}

data class RawMkvCandidate(
    val source: SourcePriority,
    val quality: MkvQuality,
    val hosterLabel: String,
    val rawUrl: String,
    val pageReferer: String,
    val sizeLabel: String? = null,
    val isBatchArchive: Boolean = false,
    val container: MediaContainer = MediaContainer.MKV
)

data class ResolvedMkvMedia(
    val source: SourcePriority,
    val quality: MkvQuality,
    val hoster: String,
    val directUrl: String,
    val referer: String?,
    val supportsRange: Boolean,
    val sizeLabel: String? = null,
    val isBatchArchive: Boolean = false,
    val container: MediaContainer = MediaContainer.MKV,
    val isHls: Boolean = false
)

data class CascadeDiagnostics(
    val attemptedSources: List<SourcePriority>,
    val errors: Map<SourcePriority, String>,
    val cloudflareBlockedHosts: Set<String>
) {
    fun toSummaryString(): String {
        if (errors.isEmpty()) return "Tidak ada sumber yang merespons."
        return errors.entries.joinToString(" | ") { (src, msg) ->
            "${src.id}: ${msg.take(48)}"
        }
    }
}

sealed class CascadeResult {
    data class Success(
        val selected: ResolvedMkvMedia,
        val alternatives: List<ResolvedMkvMedia>,
        val diagnostics: CascadeDiagnostics
    ) : CascadeResult()

    data class Failure(
        val reason: String,
        val diagnostics: CascadeDiagnostics
    ) : CascadeResult()
}

interface MkvSourceProvider {
    val priority: SourcePriority
    fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality> = MkvQuality.ALL_DESC.toSet()
    ): List<RawMkvCandidate>
}

data class ParsedTitle(
    val canonicalBase: String,
    val season: Int?,
    val tokens: Set<String>
)

internal object TitleMatcher {
    private val nonWord = Regex("[^a-z0-9\\s]+")
    private val brackets = Regex("[\\[\\](){}]")
    private val stopWords = setOf(
        "sub", "indo", "subtitle", "indonesia", "batch", "bd", "episode", "eps",
        "the", "tv", "ova", "ona", "movie", "special"
    )
    private val seasonPatterns = listOf(
        Regex("""\bseason\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bs(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)(?:nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpart\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    )
    private val romanSeasonRegex = Regex("""\b(II|III|IV|V)\b""")

    fun parse(input: String): ParsedTitle {
        val noBrackets = brackets.replace(input, " ")
        var season: Int? = null
        for (p in seasonPatterns) {
            val m = p.find(noBrackets)
            if (m != null) {
                season = m.groupValues[1].toIntOrNull()
                if (season != null) break
            }
        }
        if (season == null) {
            val rm = romanSeasonRegex.find(noBrackets)
            season = when (rm?.groupValues?.getOrNull(1)) {
                "II" -> 2
                "III" -> 3
                "IV" -> 4
                "V" -> 5
                else -> null
            }
        }
        var stripped = noBrackets
        for (p in seasonPatterns) stripped = p.replace(stripped, " ")
        stripped = romanSeasonRegex.replace(stripped, " ")
        val canonicalBase = nonWord.replace(stripped.lowercase(Locale.ROOT), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return ParsedTitle(
            canonicalBase = canonicalBase,
            season = season,
            tokens = tokenize(noBrackets)
        )
    }

    fun tokenize(input: String): Set<String> {
        val normalized = brackets.replace(input, " ")
            .replace(Regex("""\b(\d+)(?:nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE), "season $1")
            .replace(Regex("""\bs(\d+)\b""", RegexOption.IGNORE_CASE), "season $1")
            .replace(Regex("""\bIII\b"""), "season 3")
            .replace(Regex("""\bII\b"""), "season 2")
            .replace(Regex("""\bIV\b"""), "season 4")
        return nonWord.replace(normalized.lowercase(Locale.ROOT), " ")
            .split(" ")
            .map { it.trim() }
            .filter { token ->
                token.isNotEmpty() && token !in stopWords &&
                    (token.length >= 2 || token[0].isDigit())
            }
            .toSet()
    }

    fun score(query: String, candidate: String): Double {
        val pq = parse(query)
        val pc = parse(candidate)
        if (pq.tokens.isEmpty() || pc.tokens.isEmpty()) return 0.0
        val inter = pq.tokens.intersect(pc.tokens).size.toDouble()
        val union = pq.tokens.union(pc.tokens).size.toDouble()
        var sc = inter / union
        if (pq.canonicalBase.length >= 3 && pc.canonicalBase.contains(pq.canonicalBase)) {
            sc += 0.25
        }
        when {
            pq.season != null && pc.season == pq.season -> sc += 0.25
            pq.season != null && pq.season > 1 && pc.season != pq.season -> sc -= 0.35
            pq.season == null && pc.season != null && pc.season > 1 -> sc -= 0.25
        }
        return sc.coerceAtLeast(0.0)
    }

    fun matchesEpisode(text: String, href: String, episodeNumber: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.toIntOrNull() == episodeNumber) return true
        val epRegex = Regex("""(?:episode|eps|ep)[\s\-_]*0*($episodeNumber)(?!\d)""", RegexOption.IGNORE_CASE)
        return epRegex.containsMatchIn(trimmed) || epRegex.containsMatchIn(href)
    }

    fun searchQueries(animeTitle: String): List<String> {
        val p = parse(animeTitle)
        val cleanFull = nonWord.replace(brackets.replace(animeTitle, " "), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val beforeColon = nonWord.replace(brackets.replace(animeTitle.substringBefore(":"), " "), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val base = p.canonicalBase
        val out = mutableListOf<String>()
        if (p.season != null && base.isNotBlank()) {
            out.add("$base Season ${p.season}")
            out.add("$base ${p.season}")
        }
        if (cleanFull.isNotBlank()) out.add(cleanFull)
        if (beforeColon.isNotBlank()) out.add(beforeColon)
        if (base.isNotBlank()) out.add(base)
        return out
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun isMkvBlock(headerText: String, rowText: String): Boolean {
        val combined = "${headerText.lowercase(Locale.ROOT)} ${rowText.lowercase(Locale.ROOT)}"
        if (combined.contains("mp4") && !combined.contains("mkv")) return false
        return combined.contains("mkv") || combined.contains("x265") || combined.contains("hevc")
    }
}

object DeadCandidateCache {
    private const val TTL_MS = 5L * 60L * 1000L
    private const val MAX_ENTRIES = 256
    private val deadUntil = ConcurrentHashMap<String, Long>()

    fun isDead(rawUrl: String): Boolean {
        val key = rawUrl.trim()
        val exp = deadUntil[key] ?: return false
        if (System.currentTimeMillis() > exp) {
            deadUntil.remove(key, exp)
            return false
        }
        return true
    }

    fun markDead(rawUrl: String) {
        val key = rawUrl.trim()
        if (key.isEmpty()) return
        if (deadUntil.size >= MAX_ENTRIES) deadUntil.clear()
        deadUntil[key] = System.currentTimeMillis() + TTL_MS
    }

    fun clear() {
        deadUntil.clear()
    }
}

object SeriesEpisodeMapCache {
    private const val TTL_MS = 30L * 60L * 1000L
    private const val MAX_SERIES = 64

    private data class Entry(
        val cachedAtMs: Long,
        val episodes: Map<Int, String>
    )

    private val map = ConcurrentHashMap<String, Entry>()

    private fun key(source: SourcePriority, seriesUrl: String): String =
        "${source.id}::${seriesUrl.trim().trimEnd('/').lowercase(Locale.ROOT)}"

    fun get(source: SourcePriority, seriesUrl: String, episodeNumber: Int): String? {
        val k = key(source, seriesUrl)
        val entry = map[k] ?: return null
        if (System.currentTimeMillis() - entry.cachedAtMs > TTL_MS) {
            map.remove(k)
            return null
        }
        return entry.episodes[episodeNumber]
    }

    fun putAll(source: SourcePriority, seriesUrl: String, episodes: Map<Int, String>) {
        if (episodes.isEmpty()) return
        if (map.size >= MAX_SERIES) map.clear()
        map[key(source, seriesUrl)] = Entry(System.currentTimeMillis(), episodes)
    }

    fun invalidate(source: SourcePriority, seriesUrl: String) {
        map.remove(key(source, seriesUrl))
    }

    fun clear() {
        map.clear()
    }

    fun extractEpisodeNumber(text: String, href: String): Int? {
        val fromText = Regex("(?:episode|eps?|ep)\\s*0*(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromText != null) return fromText
        return Regex("(?:episode|eps?|ep)[-_\\s]*0*(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE)
            .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

object SourceCircuitBreaker {
    private const val COOLDOWN_MS = 3L * 60L * 1000L
    private const val FAIL_THRESHOLD = 2

    private val failCounts = ConcurrentHashMap<SourcePriority, Int>()
    private val openUntilMs = ConcurrentHashMap<SourcePriority, Long>()

    fun isOpen(source: SourcePriority, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = openUntilMs[source] ?: return false
        if (nowMs >= until) {
            openUntilMs.remove(source)
            failCounts[source] = 0
            return false
        }
        return true
    }

    fun recordSuccess(source: SourcePriority) {
        failCounts[source] = 0
        openUntilMs.remove(source)
    }

    fun recordFailure(source: SourcePriority, nowMs: Long = System.currentTimeMillis()) {
        val count = (failCounts[source] ?: 0) + 1
        failCounts[source] = count
        if (count >= FAIL_THRESHOLD) {
            openUntilMs[source] = nowMs + COOLDOWN_MS
        }
    }

    fun resetAll() {
        failCounts.clear()
        openUntilMs.clear()
    }
}

abstract class BaseHtmlMkvProvider(
    protected val clients: IsolatedHttpClients,
    protected var baseUrl: String,
    protected val slugCache: SourceSlugCache? = null,
    private val baseUrlProvider: (() -> String)? = null,
    private val indexLookup: ((SourcePriority, String) -> String?)? = null,
    private val remoteHtmlFetcher: ((String) -> String?)? = null
) : MkvSourceProvider {

    protected fun activeBaseUrl(): String =
        (baseUrlProvider?.invoke() ?: baseUrl).trimEnd('/')

    protected fun lookupCachedOrIndexedUrl(animeTitle: String): String? {
        slugCache?.get(priority, animeTitle)?.let { return it }
        val indexed = runCatching { indexLookup?.invoke(priority, animeTitle) }.getOrNull()
        if (!indexed.isNullOrBlank()) {
            slugCache?.put(priority, animeTitle, indexed)
            return indexed
        }
        return null
    }

    protected fun fetchDoc(url: String, referer: String? = null): Document {
        val req = Request.Builder().url(url).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        return try {
            clients.sourceClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if ((resp.code == 403 || resp.code == 503) && remoteHtmlFetcher != null) {
                        val remoteHtml = runCatching { remoteHtmlFetcher?.invoke(url) }.getOrNull()
                        if (!remoteHtml.isNullOrBlank()) {
                            return Jsoup.parse(remoteHtml, url)
                        }
                    }
                    throw IllegalStateException("HTTP ${resp.code} dari $url")
                }
                val finalUrl = resp.request.url.toString()
                val html = resp.body?.string().orEmpty()
                Jsoup.parse(html, finalUrl)
            }
        } catch (e: CloudflareChallengeException) {
            val remoteHtml = runCatching { remoteHtmlFetcher?.invoke(url) }.getOrNull()
            if (!remoteHtml.isNullOrBlank()) {
                Jsoup.parse(remoteHtml, url)
            } else {
                throw e
            }
        }
    }

    protected fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8")

    protected fun harvestFallbackCandidates(
        epDoc: Document,
        epUrl: String,
        existing: List<RawMkvCandidate>
    ): List<RawMkvCandidate> {
        val seenUrls = existing.mapTo(HashSet()) { it.rawUrl }
        val extra = mutableListOf<RawMkvCandidate>()

        epDoc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").trim()
            val label = a.text().trim()
            if (!href.startsWith("http") || href in seenUrls) return@forEach
            if (HosterFilter.isBlacklisted(label, href)) return@forEach
            if (!HosterFilter.isKnownPlayableHost(href)) return@forEach
            val rowCtx = a.parent()?.text().orEmpty()
            val quality = MkvQuality.fromText("$rowCtx $label $href") ?: MkvQuality.P720
            val container = MediaContainer.detect(rowCtx, label, href)
            seenUrls.add(href)
            extra.add(
                RawMkvCandidate(
                    source = priority,
                    quality = quality,
                    hosterLabel = label.ifBlank { "Hoster" },
                    rawUrl = href,
                    pageReferer = epUrl,
                    container = container
                )
            )
        }

        epDoc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").trim()
            if (!src.startsWith("http") || src in seenUrls) return@forEach
            val low = src.lowercase(Locale.ROOT)
            if (low.contains("disqus") || low.contains("facebook") || low.contains("ads") ||
                low.contains("youtube.com") || low.contains("twitter.com") || low.contains("recaptcha")
            ) return@forEach
            if (HosterFilter.isBlacklisted("", src)) return@forEach
            seenUrls.add(src)
            extra.add(
                RawMkvCandidate(
                    source = priority,
                    quality = MkvQuality.fromText(src) ?: MkvQuality.P720,
                    hosterLabel = "EmbedPlayer",
                    rawUrl = src,
                    pageReferer = epUrl,
                    container = MediaContainer.MP4
                )
            )
        }

        return extra
    }
}

class OtakudesuSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.OTAKUDESU.defaultBaseUrl,
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.OTAKUDESU

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val root = activeBaseUrl()
        var bestAnimeUrl: String? = lookupCachedOrIndexedUrl(animeTitle)
        var searchUrl = "$root/"
        if (bestAnimeUrl == null) {
            for (q in TitleMatcher.searchQueries(animeTitle)) {
                searchUrl = "$root/?s=${encodeQuery(q)}&post_type=anime"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                bestAnimeUrl = searchDoc.select("ul.chivsrc > li")
                    .mapNotNull { li ->
                        val a = li.selectFirst("h2 > a[href]") ?: return@mapNotNull null
                        val title = a.text().trim()
                        val href = a.absUrl("href")
                        if (href.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
                    }
                    .maxByOrNull { it.first }
                    ?.takeIf { it.first >= 0.25 }
                    ?.second
                if (bestAnimeUrl != null) break
            }
        }
        if (bestAnimeUrl == null) return emptyList()
        slugCache?.put(priority, animeTitle, bestAnimeUrl)

        var epUrl = SeriesEpisodeMapCache.get(priority, bestAnimeUrl, episodeNumber)
        if (epUrl == null) {
            val animeDoc = runCatching { fetchDoc(bestAnimeUrl, searchUrl) }.getOrElse {
                slugCache?.invalidate(priority, animeTitle)
                throw it
            }
            val epBoxes = animeDoc.select("div.episodelist")
            val orderedEpLinks = if (epBoxes.size > 1) {
                epBoxes[1].select("ul li a[href]") + epBoxes[0].select("ul li a[href]")
            } else {
                animeDoc.select(".episodelist ul li a[href]")
            }
            val parsedMap = linkedMapOf<Int, String>()
            orderedEpLinks.forEach { a ->
                val href = a.absUrl("href")
                if (href.contains("/episode/")) {
                    SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)?.let { num ->
                        parsedMap.putIfAbsent(num, href)
                    }
                }
            }
            SeriesEpisodeMapCache.putAll(priority, bestAnimeUrl, parsedMap)
            epUrl = parsedMap[episodeNumber] ?: orderedEpLinks
                .firstOrNull { a ->
                    val href = a.absUrl("href")
                    href.contains("/episode/") && TitleMatcher.matchesEpisode(a.text(), href, episodeNumber)
                }?.absUrl("href") ?: return emptyList()
        }

        val epDoc = runCatching { fetchDoc(epUrl, bestAnimeUrl) }.getOrElse {
            SeriesEpisodeMapCache.invalidate(priority, bestAnimeUrl)
            throw it
        }
        val results = mutableListOf<RawMkvCandidate>()

        val downloadUls = epDoc.select("div.download ul")
        downloadUls.forEach { ul ->
            val blockHeader = ul.previousElementSibling()?.text().orEmpty()
            ul.select("li").forEach { li ->
                val strongText = li.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val container = MediaContainer.detect(blockHeader, strongText)
                val sizeLabel = li.selectFirst("i")?.text()?.trim()?.takeIf { it.isNotBlank() }

                li.select("a[href]").forEach { a ->
                    val href = a.absUrl("href").trim()
                    val label = a.text().trim()
                    if (href.startsWith("http") && !HosterFilter.isBlacklisted(label, href)) {
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = quality,
                                hosterLabel = label.ifBlank { "Hoster" },
                                rawUrl = href,
                                pageReferer = epUrl,
                                sizeLabel = sizeLabel,
                                container = container
                            )
                        )
                    }
                }
            }
        }
        results.addAll(harvestFallbackCandidates(epDoc, epUrl, results))
        return results
    }
}

class SamehadakuSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.SAMEHADAKU.defaultBaseUrl,
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.SAMEHADAKU

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val root = activeBaseUrl()
        var bestAnimeUrl: String? = lookupCachedOrIndexedUrl(animeTitle)
        var searchUrl = "$root/"
        if (bestAnimeUrl == null) {
            for (q in TitleMatcher.searchQueries(animeTitle)) {
                searchUrl = "$root/?s=${encodeQuery(q)}&post_type=anime"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                bestAnimeUrl = searchDoc.select("div.animposx > a[href], article.animpost div.animposx > a[href], article.animepost div.animposx a[href]")
                    .mapNotNull { a ->
                        val href = a.absUrl("href")
                        val title = a.selectFirst(".data .title h2")?.text()?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: a.attr("title").ifBlank { a.text() }.trim()
                        if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
                    }
                    .maxByOrNull { it.first }
                    ?.takeIf { it.first >= 0.25 }
                    ?.second
                if (bestAnimeUrl != null) break
            }
        }
        if (bestAnimeUrl == null) return emptyList()

        var seriesUrl = bestAnimeUrl
        var epUrl = if (seriesUrl.contains("/anime/")) SeriesEpisodeMapCache.get(priority, seriesUrl, episodeNumber) else null
        if (epUrl == null) {
            var animeDoc = runCatching { fetchDoc(seriesUrl, searchUrl) }.getOrElse {
                slugCache?.invalidate(priority, animeTitle)
                throw it
            }
            if (!seriesUrl.contains("/anime/")) {
                val resolvedSeries = animeDoc.selectFirst("a[href*='/anime/'], div.nvs.nvsc a[href]")?.absUrl("href")
                if (!resolvedSeries.isNullOrBlank()) {
                    seriesUrl = resolvedSeries
                    animeDoc = fetchDoc(seriesUrl, bestAnimeUrl)
                }
            }
            slugCache?.put(priority, animeTitle, seriesUrl)

            val epRows = animeDoc.select("div.lstepsiode.listeps ul li, .listeps ul li, .lstepsiode ul li, .episode-lainnya ul li")
            val parsedMap = linkedMapOf<Int, String>()
            epRows.forEach { li ->
                val badgeNum = li.selectFirst(".epsright, .epl-num, .shrink-0 a, .shrink-0 div")
                    ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                val a = li.selectFirst("h4 a[href], .lchx a[href], a[href*='-episode-'], a[href]") ?: return@forEach
                val href = a.absUrl("href")
                if (href.startsWith("http")) {
                    val num = badgeNum ?: SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)
                    if (num != null) parsedMap.putIfAbsent(num, href)
                }
            }
            SeriesEpisodeMapCache.putAll(priority, seriesUrl, parsedMap)
            epUrl = parsedMap[episodeNumber]
                ?: epRows.firstOrNull { li ->
                    val badgeNum = li.selectFirst(".epsright, .epl-num, .shrink-0 a, .shrink-0 div")
                        ?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                    val a = li.selectFirst("h4 a[href], .lchx a[href], a[href*='-episode-'], a[href]") ?: return@firstOrNull false
                    badgeNum == episodeNumber || TitleMatcher.matchesEpisode(a.text(), a.absUrl("href"), episodeNumber)
                }?.selectFirst("h4 a[href], .lchx a[href], a[href*='-episode-'], a[href]")?.absUrl("href")
                ?: animeDoc.select("a[href*='-episode-']").firstOrNull { a ->
                    TitleMatcher.matchesEpisode(a.text(), a.absUrl("href"), episodeNumber)
                }?.absUrl("href")
                ?: return emptyList()
        }

        val epDoc = runCatching { fetchDoc(epUrl, seriesUrl) }.getOrElse {
            SeriesEpisodeMapCache.invalidate(priority, seriesUrl)
            throw it
        }
        val results = mutableListOf<RawMkvCandidate>()

        epDoc.select("div.download-eps, div#downloadb").forEach { block ->
            val formatHeader = block.selectFirst("p b, p")?.text().orEmpty()
            block.select("ul li").forEach { li ->
                val strongText = li.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val container = MediaContainer.detect(formatHeader, strongText)

                li.select("a[href]").forEach { a ->
                    val href = a.absUrl("href").trim()
                    val label = a.text().trim()
                    if (href.startsWith("http") && !HosterFilter.isBlacklisted(label, href)) {
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = quality,
                                hosterLabel = label.ifBlank { "Hoster" },
                                rawUrl = href,
                                pageReferer = epUrl,
                                container = container
                            )
                        )
                    }
                }
            }
        }
        results.addAll(harvestFallbackCandidates(epDoc, epUrl, results))
        return results
    }
}

class OploverzSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.OPLOVERZ.defaultBaseUrl,
    private val backApiUrl: String = "https://backapi.oploverz.ac",
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.OPLOVERZ

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val htmlCandidates = runCatching { searchHtmlOploverz(animeTitle, episodeNumber, targetQualities) }
            .getOrDefault(emptyList())
        if (htmlCandidates.isNotEmpty()) return htmlCandidates
        return runCatching { searchBackApiOploverz(animeTitle, episodeNumber, targetQualities) }
            .getOrDefault(emptyList())
    }

    private fun searchHtmlOploverz(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val root = activeBaseUrl()
        var bestAnimeUrl: String? = lookupCachedOrIndexedUrl(animeTitle)
        var searchUrl = "$root/"
        if (bestAnimeUrl == null) {
            for (q in TitleMatcher.searchQueries(animeTitle)) {
                searchUrl = "$root/?s=${encodeQuery(q)}"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                bestAnimeUrl = searchDoc.select("div.bsx, article.bs")
                    .mapNotNull { el ->
                        val a = el.selectFirst("a[href]") ?: return@mapNotNull null
                        val href = a.absUrl("href")
                        val title = el.selectFirst("div.tt h4, h4, .tt")?.text()?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: a.attr("title").ifBlank { a.text() }.trim()
                        if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
                    }
                    .maxByOrNull { it.first }
                    ?.takeIf { it.first >= 0.25 }
                    ?.second
                if (bestAnimeUrl != null) break
            }
        }
        if (bestAnimeUrl == null) return emptyList()

        var seriesUrl = bestAnimeUrl
        var epUrl = if (seriesUrl.contains("/series/")) SeriesEpisodeMapCache.get(priority, seriesUrl, episodeNumber) else null
        if (epUrl == null) {
            var animeDoc = runCatching { fetchDoc(seriesUrl, searchUrl) }.getOrElse {
                slugCache?.invalidate(priority, animeTitle)
                throw it
            }
            if (!seriesUrl.contains("/series/")) {
                val allEpHref = animeDoc.selectFirst("div.nvs.nvsc a[href], a[aria-label='All Episodes'], a[href*='/series/'], .year a[href*='/anime/']")
                    ?.absUrl("href")
                if (!allEpHref.isNullOrBlank()) {
                    seriesUrl = allEpHref
                    animeDoc = fetchDoc(seriesUrl, bestAnimeUrl)
                }
            }
            slugCache?.put(priority, animeTitle, seriesUrl)

            val epLis = animeDoc.select("div.eplister ul li, ul#episodelist li")
            val parsedMap = linkedMapOf<Int, String>()
            epLis.forEach { li ->
                val a = li.selectFirst("a[href]") ?: return@forEach
                val href = a.absUrl("href")
                if (href.startsWith("http")) {
                    val num = li.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
                        ?: SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)
                    if (num != null) parsedMap.putIfAbsent(num, href)
                }
            }
            SeriesEpisodeMapCache.putAll(priority, seriesUrl, parsedMap)
            epUrl = parsedMap[episodeNumber] ?: epLis
                .firstOrNull { li ->
                    val num = li.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
                    val a = li.selectFirst("a[href]") ?: return@firstOrNull false
                    num == episodeNumber || TitleMatcher.matchesEpisode(a.text(), a.absUrl("href"), episodeNumber)
                }?.selectFirst("a[href]")?.absUrl("href") ?: return emptyList()
        }

        val epDoc = fetchDoc(epUrl, seriesUrl)
        val results = mutableListOf<RawMkvCandidate>()

        epDoc.select("div.soraddlx, div.mctnx, div.download-box").forEach { block ->
            val blockTitle = block.selectFirst(".sorattlx, h3, h4")?.text().orEmpty()
            block.select(".soraurlx, li").forEach { row ->
                val qualityText = row.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(qualityText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val container = MediaContainer.detect(blockTitle, qualityText)

                row.select("a[href]").forEach { a ->
                    val href = a.absUrl("href").trim()
                    val label = a.text().trim()
                    if (href.startsWith("http") && !HosterFilter.isBlacklisted(label, href)) {
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = quality,
                                hosterLabel = label.ifBlank { "Hoster" },
                                rawUrl = href,
                                pageReferer = epUrl,
                                container = container
                            )
                        )
                    }
                }
            }
        }

        epDoc.select("div.flex.flex-row.items-start").forEach { row ->
            val qualityText = row.selectFirst("div.w-20 > p")?.text().orEmpty()
            val quality = MkvQuality.fromText(qualityText) ?: return@forEach
            if (quality !in targetQualities) return@forEach
            val container = MediaContainer.detect("", qualityText)
            row.select("div.flex.flex-row.flex-wrap > a[href]").forEach { a ->
                val href = a.absUrl("href").trim()
                val label = a.text().trim()
                if (href.startsWith("http") && !HosterFilter.isBlacklisted(label, href)) {
                    results.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = quality,
                            hosterLabel = label.ifBlank { "Hoster" },
                            rawUrl = href,
                            pageReferer = epUrl,
                            container = container
                        )
                    )
                }
            }
        }

        epDoc.select("a.dsu-download[href]").forEach { a ->
            val href = a.absUrl("href").trim()
            val text = a.text().trim()
            if (href.startsWith("http")) {
                val quality = MkvQuality.fromText(text) ?: MkvQuality.P720
                if (quality in targetQualities && !HosterFilter.isBlacklisted(text, href)) {
                    results.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = quality,
                            hosterLabel = text.ifBlank { "Hoster" },
                            rawUrl = href,
                            pageReferer = epUrl,
                            container = MediaContainer.detect("", text, href)
                        )
                    )
                }
            }
        }
        results.addAll(harvestFallbackCandidates(epDoc, epUrl, results))
        return results
    }

    private fun searchBackApiOploverz(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val root = activeBaseUrl()
        val query = TitleMatcher.searchQueries(animeTitle).firstOrNull() ?: animeTitle
        val searchReq = Request.Builder()
            .url("$backApiUrl/api/series?q=${encodeQuery(query)}")
            .build()
        val seriesSlug = clients.mediaAndApiClient.newCall(searchReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return emptyList()
            var bestScore = 0.0
            var bestSlug: String? = null
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val title = obj.optString("title")
                val slug = obj.optString("slug")
                val sc = TitleMatcher.score(animeTitle, title)
                if (sc > bestScore && slug.isNotBlank()) {
                    bestScore = sc
                    bestSlug = slug
                }
            }
            if (bestScore >= 0.25) bestSlug else null
        } ?: return emptyList()

        val seriesUrl = "$root/series/$seriesSlug"
        slugCache?.put(priority, animeTitle, seriesUrl)
        val animeDoc = fetchDoc(seriesUrl, "$root/")
        val epUrl = animeDoc.select("div.eplister ul li a[href], ul#episodelist li a[href]")
            .firstOrNull { a ->
                TitleMatcher.matchesEpisode(a.text(), a.absUrl("href"), episodeNumber)
            }?.absUrl("href") ?: return emptyList()

        val epDoc = fetchDoc(epUrl, seriesUrl)
        val results = mutableListOf<RawMkvCandidate>()
        epDoc.select("div.soraddlx, div.mctnx, div.download-box").forEach { block ->
            val blockTitle = block.selectFirst(".sorattlx, h3, h4")?.text().orEmpty()
            block.select(".soraurlx, li").forEach { row ->
                val qualityText = row.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(qualityText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val container = MediaContainer.detect(blockTitle, qualityText)
                row.select("a[href]").forEach { a ->
                    val href = a.absUrl("href").trim()
                    val label = a.text().trim()
                    if (href.startsWith("http") && !HosterFilter.isBlacklisted(label, href)) {
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = quality,
                                hosterLabel = label.ifBlank { "Hoster" },
                                rawUrl = href,
                                pageReferer = epUrl,
                                container = container
                            )
                        )
                    }
                }
            }
        }
        results.addAll(harvestFallbackCandidates(epDoc, epUrl, results))
        return results
    }
}

class KusonimeSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.KUSONIME.defaultBaseUrl,
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.KUSONIME

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val root = activeBaseUrl()
        var bestPostUrl: String? = lookupCachedOrIndexedUrl(animeTitle)
        var searchUrl = "$root/"
        if (bestPostUrl == null) {
            for (q in TitleMatcher.searchQueries(animeTitle)) {
                searchUrl = "$root/?s=${encodeQuery(q)}"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                bestPostUrl = searchDoc.select("div.kover a[href], h2.episodeye a[href]")
                    .mapNotNull { a ->
                        val href = a.absUrl("href")
                        val title = a.attr("title").ifBlank { a.text() }.trim()
                        if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
                    }
                    .maxByOrNull { it.first }
                    ?.takeIf { it.first >= 0.25 }
                    ?.second
                if (bestPostUrl != null) break
            }
        }
        if (bestPostUrl == null) return emptyList()
        slugCache?.put(priority, animeTitle, bestPostUrl)

        val postDoc = runCatching { fetchDoc(bestPostUrl, searchUrl) }.getOrElse {
            slugCache?.invalidate(priority, animeTitle)
            throw it
        }
        val results = mutableListOf<RawMkvCandidate>()

        postDoc.select("div.smokeddlrh, div.dlbodz").forEach { block ->
            val header = block.selectFirst(".smokettlrh, h3")?.text().orEmpty()
            block.select("div.smokeurlrh").forEach { row ->
                val strongText = row.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val container = MediaContainer.detect(header, strongText)
                val isBatch = header.contains("batch", ignoreCase = true)

                row.select("a[href]").forEach { a ->
                    val href = a.absUrl("href").trim()
                    val label = a.text().trim()
                    if (href.startsWith("http") && !HosterFilter.isBlacklisted(label, href)) {
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = quality,
                                hosterLabel = label.ifBlank { "Hoster" },
                                rawUrl = href,
                                pageReferer = bestPostUrl,
                                isBatchArchive = isBatch,
                                container = container
                            )
                        )
                    }
                }
            }
        }
        results.addAll(harvestFallbackCandidates(postDoc, bestPostUrl, results))
        return results
    }
}

class MkvCascadeResolver(
    private val clients: IsolatedHttpClients,
    private val slugCache: SourceSlugCache? = null,
    private val baseUrlProvider: (SourcePriority) -> String = { it.defaultBaseUrl },
    private val indexLookup: ((SourcePriority, String) -> String?)? = null,
    private val remoteHtmlFetcher: ((String) -> String?)? = null,
    private val onAllSourcesFailed: (() -> Unit)? = null,
    private val providers: List<MkvSourceProvider> = listOf(
        OtakudesuSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.OTAKUDESU) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        SamehadakuSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.SAMEHADAKU) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        OploverzSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.OPLOVERZ) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        KusonimeSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.KUSONIME) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher)
    )
) {
    private val unshortener = LinkUnshortener(clients)
    private val extractorRegistry = MkvHosterExtractorRegistry(clients)

    private data class CachedEpisodeCandidates(
        val cachedAtMs: Long,
        val candidates: List<RawMkvCandidate>
    )

    private data class ProviderFetchOutcome(
        val source: SourcePriority,
        val candidates: List<RawMkvCandidate> = emptyList(),
        val cfHost: String? = null,
        val error: String? = null
    )

    private val rawCache = ConcurrentHashMap<String, CachedEpisodeCandidates>()

    private fun adaptiveProviderOrder(): List<MkvSourceProvider> {
        return providers.sortedWith(
            compareByDescending<MkvSourceProvider> { EndpointHealthScorer.sourceScore(it.priority.id) }
                .thenBy { it.priority.rank }
        )
    }

    fun warmEpisodeCandidates(animeTitle: String, episodeNumber: Int): Boolean {
        val cacheKey = "${animeTitle.lowercase(Locale.ROOT).trim()}#$episodeNumber"
        val nowMs = System.currentTimeMillis()
        val alreadyWarm = rawCache[cacheKey]?.let {
            nowMs - it.cachedAtMs <= RAW_CACHE_TTL_MS && it.candidates.isNotEmpty()
        } == true
        if (alreadyWarm) return true

        val orderedProviders = adaptiveProviderOrder().take(2)
        for (provider in orderedProviders) {
            if (SourceCircuitBreaker.isOpen(provider.priority)) continue
            val outcome = fetchFromProvider(provider, animeTitle, episodeNumber)
            if (outcome.candidates.isNotEmpty()) {
                SourceCircuitBreaker.recordSuccess(provider.priority)
                putRawCache(cacheKey, outcome.candidates, System.currentTimeMillis())
                return true
            }
        }
        return false
    }

    fun resolve(
        animeTitle: String,
        episodeNumber: Int,
        preferredQuality: MkvQuality = MkvQuality.P1080,
        allowBatchFallback: Boolean = false,
        preferCompatibleContainer: Boolean = false
    ): CascadeResult {
        val orderedProviders = adaptiveProviderOrder()
        val attempted = mutableListOf<SourcePriority>()
        val errors = linkedMapOf<SourcePriority, String>()
        val cfHosts = linkedSetOf<String>()

        val qualityOrder = listOf(preferredQuality) +
            MkvQuality.ALL_DESC.filterNot { it == preferredQuality }

        val cacheKey = "${animeTitle.lowercase(Locale.ROOT).trim()}#$episodeNumber"
        val nowMs = System.currentTimeMillis()
        val cachedRaw = rawCache[cacheKey]
            ?.takeIf { nowMs - it.cachedAtMs <= RAW_CACHE_TTL_MS }
            ?.candidates

        if (!cachedRaw.isNullOrEmpty()) {
            val usableCached = rankCandidates(cachedRaw, qualityOrder, allowBatchFallback, preferCompatibleContainer)
            val fastResolved = raceExtractCandidates(usableCached, maxResults = 3)
            if (fastResolved.isNotEmpty()) {
                return CascadeResult.Success(
                    selected = fastResolved.first(),
                    alternatives = fastResolved.drop(1),
                    diagnostics = CascadeDiagnostics(attempted, errors, cfHosts)
                )
            }
        }

        val activeProviders = orderedProviders.filter { provider ->
            if (SourceCircuitBreaker.isOpen(provider.priority)) {
                errors[provider.priority] = "cooldown sementara"
                false
            } else {
                true
            }
        }

        var idx = 0
        if (activeProviders.size >= 2) {
            val primary = activeProviders[0]
            val secondary = activeProviders[1]
            val hedgeQueue = LinkedBlockingQueue<ProviderFetchOutcome>(2)
            attempted.add(primary.priority)
            val primaryFuture = sharedProviderPool.submit {
                val outcome = runCatching {
                    fetchFromProvider(primary, animeTitle, episodeNumber)
                }.getOrElse { e ->
                    ProviderFetchOutcome(primary.priority, error = e.message ?: e::class.java.simpleName)
                }
                hedgeQueue.offer(outcome)
            }
            var secondaryFuture: Future<*>? = null
            try {
                val fastPrimary = runCatching {
                    hedgeQueue.poll(HEDGE_DELAY_MS, TimeUnit.MILLISECONDS)
                }.getOrNull()

                if (fastPrimary != null) {
                    val resolved = handleProviderOutcome(
                        fastPrimary, cacheKey, qualityOrder, allowBatchFallback, preferCompatibleContainer, attempted, errors, cfHosts
                    )
                    if (resolved != null) return resolved
                    idx = 1
                } else {
                    attempted.add(secondary.priority)
                    secondaryFuture = sharedProviderPool.submit {
                        val outcome = runCatching {
                            fetchFromProvider(secondary, animeTitle, episodeNumber)
                        }.getOrElse { e ->
                            ProviderFetchOutcome(secondary.priority, error = e.message ?: e::class.java.simpleName)
                        }
                        hedgeQueue.offer(outcome)
                    }
                    val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(16L)
                    repeat(2) {
                        val remainingNs = deadlineNs - System.nanoTime()
                        if (remainingNs <= 0L) return@repeat
                        val done = runCatching {
                            hedgeQueue.poll(remainingNs, TimeUnit.NANOSECONDS)
                        }.getOrNull() ?: return@repeat
                        val resolved = handleProviderOutcome(
                            done, cacheKey, qualityOrder, allowBatchFallback, preferCompatibleContainer, attempted, errors, cfHosts
                        )
                        if (resolved != null) return resolved
                    }
                    idx = 2
                }
            } finally {
                if (!primaryFuture.isDone) primaryFuture.cancel(true)
                secondaryFuture?.let { if (!it.isDone) it.cancel(true) }
            }
        }

        while (idx < activeProviders.size) {
            if (Thread.currentThread().isInterrupted) break
            val provider = activeProviders[idx++]
            attempted.add(provider.priority)
            val outcome = fetchFromProvider(provider, animeTitle, episodeNumber)
            val resolved = handleProviderOutcome(
                outcome, cacheKey, qualityOrder, allowBatchFallback, preferCompatibleContainer, attempted, errors, cfHosts
            )
            if (resolved != null) return resolved
        }

        onAllSourcesFailed?.invoke()
        val diagnostics = CascadeDiagnostics(attempted, errors, cfHosts)
        return CascadeResult.Failure(
            reason = "Sumber gagal (${diagnostics.toSummaryString()})",
            diagnostics = diagnostics
        )
    }

    private fun fetchFromProvider(
        provider: MkvSourceProvider,
        animeTitle: String,
        episodeNumber: Int
    ): ProviderFetchOutcome {
        val t0 = System.currentTimeMillis()
        return try {
            val list = provider.findEpisodeMkvCandidates(animeTitle, episodeNumber, MkvQuality.ALL_DESC.toSet())
            if (list.isNotEmpty()) {
                EndpointHealthScorer.recordSourceSuccess(provider.priority.id, System.currentTimeMillis() - t0)
            }
            ProviderFetchOutcome(provider.priority, candidates = list)
        } catch (cf: CloudflareChallengeException) {
            EndpointHealthScorer.recordSourceFailure(provider.priority.id)
            ProviderFetchOutcome(provider.priority, cfHost = cf.host, error = "403 CF")
        } catch (e: Exception) {
            SourceCircuitBreaker.recordFailure(provider.priority)
            EndpointHealthScorer.recordSourceFailure(provider.priority.id)
            ProviderFetchOutcome(provider.priority, error = e.message ?: e::class.java.simpleName)
        }
    }

    private fun handleProviderOutcome(
        outcome: ProviderFetchOutcome,
        cacheKey: String,
        qualityOrder: List<MkvQuality>,
        allowBatchFallback: Boolean,
        preferCompatibleContainer: Boolean,
        attempted: List<SourcePriority>,
        errors: MutableMap<SourcePriority, String>,
        cfHosts: MutableSet<String>
    ): CascadeResult.Success? {
        if (outcome.cfHost != null) {
            cfHosts.add(outcome.cfHost)
        }
        if (outcome.error != null) {
            errors[outcome.source] = outcome.error
            return null
        }
        if (outcome.candidates.isEmpty()) {
            errors[outcome.source] = "judul/episode tidak ditemukan"
            return null
        }
        SourceCircuitBreaker.recordSuccess(outcome.source)
        putRawCache(cacheKey, outcome.candidates, System.currentTimeMillis())
        val usable = rankCandidates(outcome.candidates, qualityOrder, allowBatchFallback, preferCompatibleContainer)
        if (usable.isEmpty()) {
            errors[outcome.source] = "hanya arsip batch"
            return null
        }
        val resolvedList = raceExtractCandidates(usable, maxResults = 3)
        if (resolvedList.isNotEmpty()) {
            return CascadeResult.Success(
                selected = resolvedList.first(),
                alternatives = resolvedList.drop(1),
                diagnostics = CascadeDiagnostics(attempted, errors, cfHosts)
            )
        }
        errors[outcome.source] = "${usable.size} link hoster gagal diekstrak/mati"
        return null
    }

    private fun putRawCache(cacheKey: String, candidates: List<RawMkvCandidate>, nowMs: Long) {
        if (rawCache.size > 64) rawCache.clear()
        rawCache[cacheKey] = CachedEpisodeCandidates(nowMs, candidates)
    }

    private fun rankCandidates(
        candidates: List<RawMkvCandidate>,
        qualityOrder: List<MkvQuality>,
        allowBatchFallback: Boolean,
        preferCompatibleContainer: Boolean
    ): List<RawMkvCandidate> {
        fun containerRank(c: MediaContainer): Int {
            return if (preferCompatibleContainer) {
                when (c) {
                    MediaContainer.MP4 -> 0
                    MediaContainer.HLS -> 1
                    MediaContainer.UNKNOWN -> 2
                    MediaContainer.MKV -> 3
                }
            } else {
                c.rank
            }
        }
        return candidates
            .filter { allowBatchFallback || !it.isBatchArchive }
            .sortedWith(
                compareBy<RawMkvCandidate> { qualityOrder.indexOf(it.quality).let { idx -> if (idx < 0) 99 else idx } }
                    .thenBy { containerRank(it.container) }
                    .thenByDescending { EndpointHealthScorer.score(it.hosterLabel) }
                    .thenBy { HosterFilter.rank(it.hosterLabel, it.rawUrl) }
            )
    }

    private fun raceExtractCandidates(
        candidates: List<RawMkvCandidate>,
        maxResults: Int = 3
    ): List<ResolvedMkvMedia> {
        val fresh = candidates.filterNot { DeadCandidateCache.isDead(it.rawUrl) }
        val batch = (if (fresh.isNotEmpty()) fresh else candidates).take(8)
        if (batch.isEmpty()) return emptyList()
        val cs = ExecutorCompletionService<ResolvedMkvMedia?>(sharedExtractionPool)
        val futures = ArrayList<Future<ResolvedMkvMedia?>>(batch.size)
        val results = mutableListOf<ResolvedMkvMedia>()
        try {
            for (candidate in batch) {
                futures.add(
                    cs.submit {
                        runCatching {
                            val finalUrl = unshortener.unshorten(candidate.rawUrl, candidate.pageReferer, maxHops = 5)
                            val media = extractorRegistry.extract(finalUrl, candidate.pageReferer)
                            if (media == null) {
                                DeadCandidateCache.markDead(candidate.rawUrl)
                                EndpointHealthScorer.recordFailure(candidate.hosterLabel)
                                return@runCatching null
                            }
                            if (!media.isArchive && !extractorRegistry.verifyPlayableMedia(media)) {
                                DeadCandidateCache.markDead(candidate.rawUrl)
                                EndpointHealthScorer.recordFailure(media.hoster)
                                return@runCatching null
                            }
                            EndpointHealthScorer.recordSuccess(media.hoster)
                            val detectedContainer = when {
                                media.isHls -> MediaContainer.HLS
                                media.directUrl.contains(".mp4", ignoreCase = true) -> MediaContainer.MP4
                                media.directUrl.contains(".mkv", ignoreCase = true) -> MediaContainer.MKV
                                else -> candidate.container
                            }
                            ResolvedMkvMedia(
                                source = candidate.source,
                                quality = candidate.quality,
                                hoster = media.hoster,
                                directUrl = media.directUrl,
                                referer = media.referer,
                                supportsRange = media.supportsRange,
                                sizeLabel = candidate.sizeLabel,
                                isBatchArchive = candidate.isBatchArchive || media.isArchive,
                                container = detectedContainer,
                                isHls = media.isHls
                            )
                        }.getOrNull()
                    }
                )
            }
            var deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(7L)
            val firstWinGraceNs = TimeUnit.MILLISECONDS.toNanos(400L)
            repeat(batch.size) {
                if (Thread.currentThread().isInterrupted) return@repeat
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return@repeat
                val fut = runCatching { cs.poll(remainingNs, TimeUnit.NANOSECONDS) }.getOrNull() ?: return@repeat
                val item = runCatching { fut.get() }.getOrNull()
                if (item != null) {
                    results.add(item)
                    if (results.size == 1) {
                        val graceDeadline = System.nanoTime() + firstWinGraceNs
                        if (graceDeadline < deadlineNs) deadlineNs = graceDeadline
                    }
                    if (results.size >= maxResults) return@repeat
                }
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
        return results
    }

    companion object {
        private const val RAW_CACHE_TTL_MS = 20L * 60L * 1000L
        private const val HEDGE_DELAY_MS = 1_200L

        private val extractSeq = AtomicInteger(1)
        private val sharedExtractionPool: ExecutorService = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "milkglass-extract-${extractSeq.getAndIncrement()}").apply {
                isDaemon = true
            }
        }

        private val providerSeq = AtomicInteger(1)
        private val sharedProviderPool: ExecutorService = Executors.newFixedThreadPool(8) { r ->
            Thread(r, "milkglass-provider-${providerSeq.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    }
}

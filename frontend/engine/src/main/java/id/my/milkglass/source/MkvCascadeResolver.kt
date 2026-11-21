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
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Piramida Prioritas Sumber (berlaku per resolusi):
 * watermarkTier 0 = Bersih tanpa watermark permanen (Puncak Piramida: Samehadaku, NontonAnimeID)
 * watermarkTier 1 = Watermark sudut / ringan (Oploverz)
 * watermarkTier 2 = Watermark berat / banner iklan atau batch (Dasar Piramida: Otakudesu, Kusonime)
 */
enum class SourcePriority(
    val rank: Int,
    val id: String,
    val defaultBaseUrl: String,
    val watermarkTier: Int
) {
    SAMEHADAKU(1, "samehadaku", "https://v2.samehadaku.how", 0),
    OPLOVERZ(2, "oploverz", "https://oploverz.ch", 1),
    NONTONANIMEID(3, "nontonanimeid", "https://nontonanimeid.stream", 0),
    OTAKUDESU(4, "otakudesu", "https://otakudesu.blog", 2),
    ANIMASU(5, "animasu", "https://animasu.love", 0),
    KUSONIME(6, "kusonime", "https://kusonime.com", 2)
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
    val isHls: Boolean = false,
    val extraHeaders: Map<String, String> = emptyMap()
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
        val diagnostics: CascadeDiagnostics,
        val availableQualities: List<MkvQuality> = listOf(selected.quality)
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
    val tokens: Set<String>,
    val part: Int? = null,
    val formatKind: String = "TV"
)

internal object TitleMatcher {
    private val nonWord = Regex("[^a-z0-9\\s]+")
    private val brackets = Regex("[\\[\\](){}]")
    private val stopWords = setOf(
        "sub", "indo", "subtitle", "indonesia", "batch", "bd", "episode", "eps",
        "the", "tv"
    )
    private val seasonPatterns = listOf(
        Regex("""\bseason\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bs(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
    )
    private val partPatterns = listOf(
        Regex("""\b(?:part|cour)\s*(\d+)\b""", RegexOption.IGNORE_CASE)
    )
    private val movieRegex = Regex("""\b(?:movie|gekijouban|film)\b""", RegexOption.IGNORE_CASE)
    private val ovaRegex = Regex("""\b(?:ova|oad)\b""", RegexOption.IGNORE_CASE)
    private val onaRegex = Regex("""\bona\b""", RegexOption.IGNORE_CASE)
    private val sideStoryRegex = Regex("""\b(?:side\s*story|gaiden|spin[\s-]*off)\b""", RegexOption.IGNORE_CASE)
    private val specialRegex = Regex("""\b(?:special|recap|sp)\b""", RegexOption.IGNORE_CASE)
    private val romanSeasonRegex = Regex("""\b(II|III|IV|V)\b""")
    private val whitespaceRegex = Regex("\\s+")
    private val ordinalSeasonRegex = Regex("""\b(\d+)(?:nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
    private val shortSeasonRegex = Regex("""\bs(\d+)\b""", RegexOption.IGNORE_CASE)
    private val roman3Regex = Regex("""\bIII\b""", RegexOption.IGNORE_CASE)
    private val roman2Regex = Regex("""\bII\b""", RegexOption.IGNORE_CASE)
    private val roman4Regex = Regex("""\bIV\b""", RegexOption.IGNORE_CASE)
    private val roman5Regex = Regex("""\bV\b""", RegexOption.IGNORE_CASE)

    private val parseCache = java.util.concurrent.ConcurrentHashMap<String, ParsedTitle>()

    /** Memoized: judul indeks diparse berulang kali saat pencocokan; regex-nya mahal. */
    fun parse(input: String): ParsedTitle {
        parseCache[input]?.let { return it }
        val p = parseUncached(input)
        if (parseCache.size >= 20_000) parseCache.clear()
        parseCache[input] = p
        return p
    }

    private fun parseUncached(input: String): ParsedTitle {
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
        var part: Int? = null
        for (p in partPatterns) {
            val m = p.find(noBrackets)
            if (m != null) {
                part = m.groupValues[1].toIntOrNull()
                if (part != null) break
            }
        }
        val formatKind = when {
            movieRegex.containsMatchIn(noBrackets) -> "MOVIE"
            ovaRegex.containsMatchIn(noBrackets) -> "OVA"
            sideStoryRegex.containsMatchIn(noBrackets) -> "SIDE_STORY"
            specialRegex.containsMatchIn(noBrackets) -> "SPECIAL"
            onaRegex.containsMatchIn(noBrackets) -> "ONA"
            else -> "TV"
        }
        var stripped = noBrackets
        for (p in seasonPatterns) stripped = p.replace(stripped, " ")
        for (p in partPatterns) stripped = p.replace(stripped, " ")
        stripped = romanSeasonRegex.replace(stripped, " ")
        val canonicalBase = nonWord.replace(stripped.lowercase(Locale.ROOT), " ")
            .replace(whitespaceRegex, " ")
            .trim()
        return ParsedTitle(
            canonicalBase = canonicalBase,
            season = season,
            tokens = tokenize(noBrackets),
            part = part,
            formatKind = formatKind
        )
    }

    fun tokenize(input: String): Set<String> {
        val normalized = brackets.replace(input, " ")
            .replace(ordinalSeasonRegex, "season $1")
            .replace(shortSeasonRegex, "season $1")
            .replace(roman3Regex, "season 3")
            .replace(roman2Regex, "season 2")
            .replace(roman4Regex, "season 4")
            .replace(roman5Regex, "season 5")
        return nonWord.replace(normalized.lowercase(Locale.ROOT), " ")
            .split(" ")
            .map { it.trim() }
            .filter { token ->
                token.isNotEmpty() && token !in stopWords &&
                    (token.length >= 2 || token[0].isDigit())
            }
            .toSet()
    }

    private val trailingYearRegex = Regex("""(?:19|20)\d{2}$""")

    fun score(query: String, candidate: String): Double {
        val pq = parse(query)
        val pc = parse(candidate)
        if (pq.tokens.isEmpty() || pc.tokens.isEmpty()) return 0.0
        var interCount = 0
        for (t in pq.tokens) if (t in pc.tokens) interCount++
        val inter = interCount.toDouble()
        val union = (pq.tokens.size + pc.tokens.size - interCount).toDouble()
        var sc = inter / union
        if (pq.canonicalBase.length >= 3 && pc.canonicalBase.contains(pq.canonicalBase)) {
            sc += 0.25
        }
        // Toleran spasi, simbol, dan akhiran tahun rilis (mis. "HunterxHunter" == "Hunter x Hunter (2011)")
        val qCompact = pq.canonicalBase.replace(" ", "")
        val cCompact = pc.canonicalBase.replace(" ", "")
        val qNoYear = trailingYearRegex.replace(qCompact, "")
        val cNoYear = trailingYearRegex.replace(cCompact, "")
        if (qCompact.length >= 4 && cCompact.length >= 4) {
            when {
                qCompact == cCompact || (qNoYear.length >= 4 && qNoYear == cNoYear) -> sc = maxOf(sc, 0.95)
                cCompact.contains(qCompact) || qCompact.contains(cCompact) ||
                    (qNoYear.length >= 4 && cNoYear.length >= 4 && (cNoYear.contains(qNoYear) || qNoYear.contains(cNoYear))) ->
                    sc = maxOf(sc, 0.72)
            }
        }
        if (pq.formatKind != pc.formatKind) {
            // Jangan tukar TV series dengan Movie / OVA / Special / Side Story
            sc -= 0.95
        }
        when {
            pq.season != null && pc.season == pq.season -> sc += 0.25
            pq.season != null && pq.season > 1 && pc.season != pq.season -> sc -= 0.90
            pq.season == null && pc.season != null && pc.season > 1 -> sc -= 0.35
        }
        when {
            pq.part != null && pc.part == pq.part -> sc += 0.15
            pq.part != null && pc.part != null && pq.part != pc.part -> sc -= 0.55
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
            .replace(whitespaceRegex, " ")
            .trim()
        val beforeColon = nonWord.replace(brackets.replace(animeTitle.substringBefore(":"), " "), " ")
            .replace(whitespaceRegex, " ")
            .trim()
        val base = p.canonicalBase
        val out = mutableListOf<String>()
        if (p.season != null && base.isNotBlank()) {
            out.add("$base Season ${p.season}")
            out.add("$base ${p.season}")
        }
        val noYear = cleanFull.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ").replace(whitespaceRegex, " ").trim()
        if (noYear.isNotBlank() && noYear != cleanFull) out.add(noYear)
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

    private val EP_TEXT_REGEX = Regex("(?:episode|eps?|ep)\\s*0*(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE)
    private val EP_HREF_REGEX = Regex("(?:episode|eps?|ep)[-_\\s]*0*(\\d{1,4})(?!\\d)", RegexOption.IGNORE_CASE)
    private val SLUG_EP_REGEX = Regex("-ep-(\\d+)$", RegexOption.IGNORE_CASE)

    fun extractEpisodeNumberFromSlug(slug: String): Int =
        SLUG_EP_REGEX.find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

    fun extractEpisodeNumber(text: String, href: String): Int? {
        val fromText = EP_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromText != null) return fromText
        return EP_HREF_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
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
        val count = failCounts.compute(source) { _, prev -> (prev ?: 0) + 1 } ?: 1
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
                    container = MediaContainer.UNKNOWN
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
                if (!Thread.currentThread().isInterrupted) slugCache?.invalidate(priority, animeTitle)
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
            if (!Thread.currentThread().isInterrupted) SeriesEpisodeMapCache.invalidate(priority, bestAnimeUrl)
            throw it
        }
        val results = mutableListOf<RawMkvCandidate>()

        val downloadUls = epDoc.select("div.download ul")
        downloadUls.forEachIndexed { ulIdx, ul ->
            val blockHeader = ul.previousElementSibling()?.text().orEmpty()
            val isImplicitMkvUl = downloadUls.size >= 2 && ulIdx == downloadUls.lastIndex
            ul.select("li").forEach { li ->
                val strongText = li.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val detected = MediaContainer.detect(blockHeader, strongText)
                val container = if (detected == MediaContainer.UNKNOWN && isImplicitMkvUl && !strongText.contains("mp4", ignoreCase = true)) {
                    MediaContainer.MKV
                } else detected
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
        results.addAll(harvestOtakudesuMirrorStreams(epDoc, epUrl, root, targetQualities, results))
        results.addAll(harvestFallbackCandidates(epDoc, epUrl, results))
        return results
    }

    private fun harvestOtakudesuMirrorStreams(
        epDoc: org.jsoup.nodes.Document,
        epUrl: String,
        root: String,
        targetQualities: Set<MkvQuality>,
        existing: List<RawMkvCandidate>
    ): List<RawMkvCandidate> {
        val mirrorLinks = epDoc.select("ul.m360p li a[data-content], ul.m480p li a[data-content], ul.m720p li a[data-content]")
        if (mirrorLinks.isEmpty()) return emptyList()
        val nonce = runCatching {
            val form = okhttp3.FormBody.Builder().add("action", "aa1208d27f29ca340c92c66d1926f13f").build()
            val req = okhttp3.Request.Builder()
                .url("$root/wp-admin/admin-ajax.php")
                .post(form)
                .header("Referer", epUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            clients.sourceClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                org.json.JSONObject(resp.body?.string().orEmpty()).optString("data").takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: return emptyList()

        val seenUrls = existing.mapTo(HashSet()) { it.rawUrl }
        val out = mutableListOf<RawMkvCandidate>()
        for (a in mirrorLinks) {
            if (out.size >= 4) break
            val label = a.text().trim()
            if (HosterFilter.isBlacklisted(label, label)) continue
            val ulClass = a.parents().firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
            val quality = MkvQuality.fromText(ulClass) ?: MkvQuality.P720
            if (quality !in targetQualities) continue
            val payload = runCatching {
                val decoded = String(android.util.Base64.decode(a.attr("data-content"), android.util.Base64.DEFAULT))
                org.json.JSONObject(decoded)
            }.getOrNull() ?: continue
            val id = payload.optString("id")
            val i = payload.optString("i")
            val q = payload.optString("q")
            if (id.isBlank() || q.isBlank()) continue
            val embedSrc = runCatching {
                val form = okhttp3.FormBody.Builder()
                    .add("id", id)
                    .add("i", i)
                    .add("q", q)
                    .add("nonce", nonce)
                    .add("action", "2a3505c93b0035d3f455df82bf976b84")
                    .build()
                val req = okhttp3.Request.Builder()
                    .url("$root/wp-admin/admin-ajax.php")
                    .post(form)
                    .header("Referer", epUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                clients.sourceClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val dataB64 = org.json.JSONObject(resp.body?.string().orEmpty()).optString("data")
                    if (dataB64.isBlank()) return@use null
                    val iframeHtml = String(android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT))
                    org.jsoup.Jsoup.parse(iframeHtml, root).selectFirst("iframe[src]")?.absUrl("src")?.trim()
                }
            }.getOrNull()
            if (!embedSrc.isNullOrBlank() && embedSrc.startsWith("http") &&
                !HosterFilter.isBlacklisted(label, embedSrc) && seenUrls.add(embedSrc)
            ) {
                out.add(
                    RawMkvCandidate(
                        source = priority,
                        quality = quality,
                        hosterLabel = label.ifBlank { "StreamMirror" },
                        rawUrl = embedSrc,
                        pageReferer = epUrl,
                        container = MediaContainer.MP4
                    )
                )
            }
        }
        return out
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
                if (!Thread.currentThread().isInterrupted) slugCache?.invalidate(priority, animeTitle)
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
            if (!Thread.currentThread().isInterrupted) SeriesEpisodeMapCache.invalidate(priority, seriesUrl)
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
        results.addAll(harvestSamehadakuPlayerMirrors(epDoc, epUrl, root, targetQualities, results))
        results.addAll(harvestFallbackCandidates(epDoc, epUrl, results))
        return results
    }

    private fun harvestSamehadakuPlayerMirrors(
        epDoc: org.jsoup.nodes.Document,
        epUrl: String,
        root: String,
        targetQualities: Set<MkvQuality>,
        existing: List<RawMkvCandidate>
    ): List<RawMkvCandidate> {
        val seenUrls = existing.mapTo(HashSet()) { it.rawUrl }
        val out = mutableListOf<RawMkvCandidate>()
        // 1) Panen iframe default yang SUDAH ada di HTML awal (0 request AJAX tambahan)
        for (ifr in epDoc.select("#player_embed iframe[src], .player-embed iframe[src], iframe[src]")) {
            val src = ifr.absUrl("src").trim()
            if (src.startsWith("http") && !HosterFilter.isBlacklisted("Embed", src) && seenUrls.add(src)) {
                val q = MkvQuality.fromText(src) ?: MkvQuality.P1080
                if (q in targetQualities) {
                    out.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = q,
                            hosterLabel = if (src.contains("icefile", ignoreCase = true)) "Icefile" else "SamehadakuEmbed",
                            rawUrl = src,
                            pageReferer = epUrl,
                            container = MediaContainer.MKV
                        )
                    )
                }
            }
        }
        if (out.isNotEmpty()) return out

        val options = epDoc.select("div.east_player_option[data-post], li div[data-post][data-nume]")
        if (options.isEmpty()) return emptyList()
        for (opt in options) {
            if (out.size >= 2) break
            val label = opt.text().trim()
            if (HosterFilter.isBlacklisted(label, label)) continue
            val postId = opt.attr("data-post").trim()
            val nume = opt.attr("data-nume").trim()
            val type = opt.attr("data-type").trim().ifBlank { "schtml" }
            if (postId.isBlank() || nume.isBlank()) continue
            val quality = MkvQuality.fromText(label) ?: MkvQuality.P1080
            if (quality !in targetQualities) continue
            val embedSrc = runCatching {
                val form = okhttp3.FormBody.Builder()
                    .add("action", "player_ajax")
                    .add("post", postId)
                    .add("nume", nume)
                    .add("type", type)
                    .build()
                val req = okhttp3.Request.Builder()
                    .url("$root/wp-admin/admin-ajax.php")
                    .post(form)
                    .header("Referer", epUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                clients.sourceClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string().orEmpty()
                    org.jsoup.Jsoup.parse(html, root).selectFirst("iframe[src]")?.absUrl("src")?.trim()
                }
            }.getOrNull()
            if (!embedSrc.isNullOrBlank() && embedSrc.startsWith("http") &&
                !HosterFilter.isBlacklisted(label, embedSrc) && seenUrls.add(embedSrc)
            ) {
                out.add(
                    RawMkvCandidate(
                        source = priority,
                        quality = quality,
                        hosterLabel = label.ifBlank { "SamehadakuStream" },
                        rawUrl = embedSrc,
                        pageReferer = epUrl,
                        container = MediaContainer.MKV
                    )
                )
            }
        }
        return out
    }
}


class NontonAnimeIdSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.NONTONANIMEID.defaultBaseUrl,
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.NONTONANIMEID

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
                searchUrl = "$root/?s=${encodeQuery(q)}"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                bestAnimeUrl = searchDoc.select("a[href*='/anime/']")
                    .mapNotNull { a ->
                        val href = a.absUrl("href").trim()
                        val title = a.attr("title").ifBlank { a.selectFirst("h2, h3, .title")?.text() ?: a.text() }.trim()
                        if (href.isBlank() || title.length < 2 || href.contains("/episode")) null
                        else TitleMatcher.score(animeTitle, title) to href
                    }
                    .maxByOrNull { it.first }
                    ?.takeIf { it.first >= 0.30 }
                    ?.second
                if (bestAnimeUrl != null) break
            }
        }
        if (bestAnimeUrl == null) return emptyList()
        slugCache?.put(priority, animeTitle, bestAnimeUrl)

        var epUrl = SeriesEpisodeMapCache.get(priority, bestAnimeUrl, episodeNumber)
        if (epUrl == null) {
            val animeDoc = fetchDoc(bestAnimeUrl, searchUrl)
            val parsedMap = linkedMapOf<Int, String>()
            animeDoc.select("a[href*='episode']").forEach { a ->
                val href = a.absUrl("href").trim()
                if (href.startsWith("http")) {
                    SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)?.let { num ->
                        parsedMap.putIfAbsent(num, href)
                    }
                }
            }
            SeriesEpisodeMapCache.putAll(priority, bestAnimeUrl, parsedMap)
            epUrl = parsedMap[episodeNumber] ?: "${bestAnimeUrl.trimEnd('/')}/episode-$episodeNumber/"
        }

        val epDoc = fetchDoc(epUrl, bestAnimeUrl)
        val results = mutableListOf<RawMkvCandidate>()
        epDoc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").trim()
            val label = a.text().trim()
            if (!href.startsWith("http") || HosterFilter.isBlacklisted(label, href)) return@forEach
            if (!HosterFilter.isKnownPlayableHost(href)) return@forEach
            val q = MkvQuality.fromText("$label $href") ?: MkvQuality.P720
            if (q !in targetQualities) return@forEach
            results.add(
                RawMkvCandidate(
                    source = priority,
                    quality = q,
                    hosterLabel = label.ifBlank { "GoFile" },
                    rawUrl = href,
                    pageReferer = epUrl,
                    container = MediaContainer.MP4
                )
            )
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
        var epUrl = if (seriesUrl.contains("/series/") || seriesUrl.contains("/anime/")) SeriesEpisodeMapCache.get(priority, seriesUrl, episodeNumber) else null
        if (epUrl == null) {
            var animeDoc = runCatching { fetchDoc(seriesUrl, searchUrl) }.getOrElse {
                if (!Thread.currentThread().isInterrupted) slugCache?.invalidate(priority, animeTitle)
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

        val epDoc = runCatching { fetchDoc(epUrl, seriesUrl) }.getOrElse {
            if (!Thread.currentThread().isInterrupted) SeriesEpisodeMapCache.invalidate(priority, seriesUrl)
            throw it
        }
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
            if (!Thread.currentThread().isInterrupted) slugCache?.invalidate(priority, animeTitle)
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


class AnimasuSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.ANIMASU.defaultBaseUrl,
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.ANIMASU

    private fun decodeBase64Safe(raw: String): String {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return ""
        val pad = (4 - (cleaned.length % 4)) % 4
        return runCatching {
            String(Base64.getDecoder().decode(cleaned + "=".repeat(pad)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun extractIframeSrc(fragment: String): String? {
        if (fragment.isBlank()) return null
        val doc = Jsoup.parseBodyFragment(fragment)
        val src = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            ?: doc.selectFirst("iframe[data-src]")?.attr("data-src")?.trim()
        return src?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun unwrapBerkasDrive(url: String): String? {
        val low = url.lowercase(Locale.ROOT)
        if (!low.contains("berkasdrive.")) return null
        val idParam = Regex("""[?&]id=([^&#]+)""").find(url)?.groupValues?.getOrNull(1) ?: return null
        val decoded = decodeBase64Safe(idParam).trim()
        return decoded.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun detectMirrorQuality(label: String, url: String): MkvQuality {
        val combined = "${label.lowercase(Locale.ROOT)} ${url.lowercase(Locale.ROOT)}"
        return when {
            combined.contains("1080") || combined.contains("fhd") || combined.contains("fullhd") -> MkvQuality.P1080
            combined.contains("720") || combined.contains("hd") -> MkvQuality.P720
            combined.contains("480") -> MkvQuality.P480
            combined.contains("360") -> MkvQuality.P360
            else -> MkvQuality.P720
        }
    }

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val root = activeBaseUrl()
        var seriesUrl = lookupCachedOrIndexedUrl(animeTitle)
        if (seriesUrl == null) {
            var bestScore = 0.0
            for (query in TitleMatcher.searchQueries(animeTitle)) {
                val searchUrl = "$root/?s=${encodeQuery(query)}"
                val doc = fetchDoc(searchUrl, referer = "$root/")
                doc.select(".postbody .listupd .bs .bsx > a[href], .listupd .bs .bsx > a[href]").forEach { a ->
                    val href = a.absUrl("href").trim()
                    if (!href.contains("/anime/", ignoreCase = true)) return@forEach
                    val titleText = a.selectFirst(".tt")?.text()?.trim()
                        ?.ifBlank { a.attr("title").trim() }
                        ?: a.text().trim()
                    val sc = TitleMatcher.score(animeTitle, titleText)
                    if (sc > bestScore && sc >= 0.45) {
                        bestScore = sc
                        seriesUrl = href
                    }
                }
                if (bestScore >= 0.72) break
            }
            seriesUrl?.let { slugCache?.put(priority, animeTitle, it) }
        }
        val resolvedSeriesUrl = seriesUrl ?: return emptyList()

        var epUrl = SeriesEpisodeMapCache.get(priority, resolvedSeriesUrl, episodeNumber)
        if (epUrl == null) {
            val seriesDoc = fetchDoc(resolvedSeriesUrl, referer = "$root/")
            val epMap = LinkedHashMap<Int, String>()
            seriesDoc.select("#daftarepisode li").forEach { li ->
                val a = li.selectFirst("a[href]") ?: return@forEach
                val href = a.absUrl("href").trim()
                val text = a.text().trim()
                if (!href.startsWith("http")) return@forEach
                val num = SeriesEpisodeMapCache.extractEpisodeNumber(text, href)
                if (num != null && !epMap.containsKey(num)) {
                    epMap[num] = href
                }
                if (epUrl == null && TitleMatcher.matchesEpisode(text, href, episodeNumber)) {
                    epUrl = href
                }
            }
            if (epMap.isNotEmpty()) {
                SeriesEpisodeMapCache.putAll(priority, resolvedSeriesUrl, epMap)
                if (epUrl == null) epUrl = epMap[episodeNumber]
            }
        }
        val resolvedEpUrl = epUrl ?: return emptyList()

        val epDoc = fetchDoc(resolvedEpUrl, referer = resolvedSeriesUrl)
        val results = mutableListOf<RawMkvCandidate>()
        val seenUrls = HashSet<String>()

        epDoc.select("select.mirror option").forEach { opt ->
            val rawVal = opt.attr("value").trim()
            if (rawVal.isEmpty()) return@forEach
            val label = opt.text().trim()
            val decoded = decodeBase64Safe(rawVal)
            val iframeSrc = extractIframeSrc(decoded) ?: return@forEach
            if (HosterFilter.isBlacklisted(label, iframeSrc)) return@forEach
            val quality = detectMirrorQuality(label, iframeSrc)
            if (quality !in targetQualities) return@forEach
            val hostLabel = runCatching { java.net.URI(iframeSrc).host.orEmpty() }.getOrDefault(label).ifBlank { "Embed" }
            if (seenUrls.add(iframeSrc)) {
                results.add(
                    RawMkvCandidate(
                        source = priority,
                        quality = quality,
                        hosterLabel = hostLabel,
                        rawUrl = iframeSrc,
                        pageReferer = resolvedEpUrl,
                        isBatchArchive = false,
                        container = MediaContainer.MP4
                    )
                )
            }
            val unwrapped = unwrapBerkasDrive(iframeSrc)
            if (unwrapped != null && !HosterFilter.isBlacklisted(label, unwrapped) && seenUrls.add(unwrapped)) {
                val uHost = runCatching { java.net.URI(unwrapped).host.orEmpty() }.getOrDefault("MiteDrive")
                results.add(
                    RawMkvCandidate(
                        source = priority,
                        quality = quality,
                        hosterLabel = uHost,
                        rawUrl = unwrapped,
                        pageReferer = iframeSrc,
                        isBatchArchive = false,
                        container = MediaContainer.MP4
                    )
                )
            }
        }

        epDoc.select("#tonton iframe[src], .video-content iframe[src]").forEach { ifr ->
            val src = ifr.absUrl("src").trim().ifBlank { ifr.attr("src").trim() }
            if (!src.startsWith("http") || !seenUrls.add(src)) return@forEach
            if (HosterFilter.isBlacklisted("default", src)) return@forEach
            val quality = detectMirrorQuality("default", src)
            if (quality !in targetQualities) return@forEach
            val hostLabel = runCatching { java.net.URI(src).host.orEmpty() }.getOrDefault("Embed")
            results.add(
                RawMkvCandidate(
                    source = priority,
                    quality = quality,
                    hosterLabel = hostLabel,
                    rawUrl = src,
                    pageReferer = resolvedEpUrl,
                    isBatchArchive = false,
                    container = MediaContainer.MP4
                )
            )
        }

        results.addAll(harvestFallbackCandidates(epDoc, resolvedEpUrl, results))
        return results
    }
}

class MkvCascadeResolver(
    private val clients: IsolatedHttpClients,
    private val slugCache: SourceSlugCache? = null,
    private val baseUrlProvider: (SourcePriority) -> String = { it.defaultBaseUrl },
    private val sourceOrderProvider: () -> List<SourcePriority> = { SourcePriority.entries },
    private val indexLookup: ((SourcePriority, String) -> String?)? = null,
    private val remoteHtmlFetcher: ((String) -> String?)? = null,
    private val onAllSourcesFailed: (() -> Unit)? = null,
    private val rawStore: RawCandidateStore? = null,
    private val onEpisodeResolved: ((String, List<RawMkvCandidate>) -> Unit)? = null,
    private val providers: List<MkvSourceProvider> = listOf(
        SamehadakuSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.SAMEHADAKU) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        OploverzSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.OPLOVERZ) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        NontonAnimeIdSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.NONTONANIMEID) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        OtakudesuSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.OTAKUDESU) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        AnimasuSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.ANIMASU) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
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

    /** poll() yang tidak menelan interupsi: flag dipulihkan supaya pemanggil bisa berhenti. */
    private fun <T> pollKeepingInterrupt(queue: java.util.concurrent.BlockingQueue<T>, timeoutNs: Long): T? =
        try {
            queue.poll(timeoutNs, TimeUnit.NANOSECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }

    private fun isCancellation(e: Throwable, abandoned: AtomicBoolean?): Boolean {
        if (abandoned?.get() == true || Thread.currentThread().isInterrupted) return true
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 5) {
            if (c is InterruptedException) return true
            if (c is java.io.IOException && c.message.equals("Canceled", ignoreCase = true)) return true
            c = c.cause
        }
        return false
    }

    private fun cancelledFailure(
        attempted: List<SourcePriority>,
        errors: Map<SourcePriority, String>,
        cfHosts: Set<String>
    ): CascadeResult.Failure = CascadeResult.Failure(
        reason = "Dibatalkan",
        diagnostics = CascadeDiagnostics(attempted, errors, cfHosts)
    )

    private fun adaptiveProviderOrder(): List<MkvSourceProvider> {
        val dynamicOrder = sourceOrderProvider()
        return providers.sortedWith(
            compareByDescending<MkvSourceProvider> { EndpointHealthScorer.sourceScore(it.priority.id) }
                .thenBy { p -> dynamicOrder.indexOf(p.priority).takeIf { it >= 0 } ?: p.priority.rank }
        )
    }

    fun cachedQualitiesFor(animeTitle: String, episodeNumber: Int): List<MkvQuality> {
        val cacheKey = "${animeTitle.lowercase(Locale.ROOT).trim()}#$episodeNumber"
        val nowMs = System.currentTimeMillis()
        val list = rawCache[cacheKey]?.takeIf { nowMs - it.cachedAtMs <= RAW_CACHE_TTL_MS }?.candidates
            ?: rawStore?.get(cacheKey, DISK_RAW_TTL_MS)
            ?: return emptyList()
        return list.filterNot { it.isBatchArchive }.map { it.quality }.distinct().sortedByDescending { it.height }
    }

    fun warmEpisodeCandidates(animeTitle: String, episodeNumber: Int): Boolean {
        val cacheKey = "${animeTitle.lowercase(Locale.ROOT).trim()}#$episodeNumber"
        val nowMs = System.currentTimeMillis()
        val alreadyWarm = rawCache[cacheKey]?.let {
            nowMs - it.cachedAtMs <= RAW_CACHE_TTL_MS && it.candidates.isNotEmpty()
        } == true || rawStore?.has(cacheKey, DISK_RAW_TTL_MS) == true
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

    /**
     * Verifikasi URL direct dari server (probe Range paralel, maks 3 dtk). URL yang kedaluwarsa/terikat IP
     * penyetor dibuang di sini, bukan baru gagal saat ExoPlayer memutar. Urutan asli dipertahankan.
     */
    fun verifyDirect(list: List<ResolvedMkvMedia>): List<ResolvedMkvMedia> {
        if (list.isEmpty()) return emptyList()
        val batch = list.take(4)
        val futures = batch.map { m ->
            sharedExtractionPool.submit<ResolvedMkvMedia?> {
                val ok = runCatching {
                    extractorRegistry.verifyPlayableMedia(
                        id.my.milkglass.extractor.ExtractedDirectMedia(
                            hoster = m.hoster,
                            directUrl = m.directUrl,
                            referer = m.referer,
                            supportsRange = m.supportsRange,
                            isArchive = m.isBatchArchive,
                            isHls = m.isHls,
                            extraHeaders = m.extraHeaders
                        )
                    )
                }.getOrDefault(false)
                if (ok) m else null
            }
        }
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L)
        try {
            return futures.mapNotNull { f ->
                try {
                    f.get((deadlineNs - System.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    /**
     * Ekstrak kandidat mentah dari luar (hint bersama di Cloudflare) secara PARALEL dengan aturan ranking,
     * dead-cache, deadline, dan pembatalan yang sama seperti jalur cascade biasa.
     */
    fun resolveFromRaw(
        raw: List<RawMkvCandidate>,
        preferredQuality: MkvQuality,
        preferCompatibleContainer: Boolean
    ): List<ResolvedMkvMedia> {
        if (raw.isEmpty()) return emptyList()
        val qualityOrder = listOf(preferredQuality) + MkvQuality.ALL_DESC.filterNot { it == preferredQuality }
        val usable = rankCandidates(raw, qualityOrder, false, preferCompatibleContainer)
        if (usable.isEmpty()) return emptyList()
        return raceExtractByQualityOrder(usable, qualityOrder, maxResults = 3)
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
            ?: rawStore?.get(cacheKey, DISK_RAW_TTL_MS)?.also { rawCache[cacheKey] = CachedEpisodeCandidates(nowMs, it) }

        if (!cachedRaw.isNullOrEmpty()) {
            val usableCached = rankCandidates(cachedRaw, qualityOrder, allowBatchFallback, preferCompatibleContainer)
            val cachedQualities = usableCached.map { it.quality }.distinct().sortedByDescending { it.height }
            val fastResolved = raceExtractByQualityOrder(usableCached, qualityOrder, maxResults = 3)
            if (fastResolved.isNotEmpty()) {
                return CascadeResult.Success(
                    selected = fastResolved.first(),
                    alternatives = fastResolved.drop(1),
                    diagnostics = CascadeDiagnostics(attempted, errors, cfHosts),
                    availableQualities = cachedQualities
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
        if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)
        if (activeProviders.size >= 2) {
            val primary = activeProviders[0]
            val secondary = activeProviders[1]
            val hedgeQueue = LinkedBlockingQueue<ProviderFetchOutcome>(2)
            val abandoned = AtomicBoolean(false)
            attempted.add(primary.priority)
            val primaryFuture = sharedProviderPool.submit {
                val outcome = runCatching {
                    fetchFromProvider(primary, animeTitle, episodeNumber, abandoned)
                }.getOrElse { e ->
                    ProviderFetchOutcome(primary.priority, error = e.message ?: e::class.java.simpleName)
                }
                hedgeQueue.offer(outcome)
            }
            var secondaryFuture: Future<*>? = null
            try {
                val fastPrimary = pollKeepingInterrupt(hedgeQueue, TimeUnit.MILLISECONDS.toNanos(HEDGE_DELAY_MS))
                if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)

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
                            fetchFromProvider(secondary, animeTitle, episodeNumber, abandoned)
                        }.getOrElse { e ->
                            ProviderFetchOutcome(secondary.priority, error = e.message ?: e::class.java.simpleName)
                        }
                        hedgeQueue.offer(outcome)
                    }
                    val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(16L)
                    repeat(2) {
                        val remainingNs = deadlineNs - System.nanoTime()
                        if (remainingNs <= 0L) return@repeat
                        val done = pollKeepingInterrupt(hedgeQueue, remainingNs) ?: return@repeat
                        val resolved = handleProviderOutcome(
                            done, cacheKey, qualityOrder, allowBatchFallback, preferCompatibleContainer, attempted, errors, cfHosts
                        )
                        if (resolved != null) return resolved
                    }
                    idx = 2
                }
            } finally {
                // Tandai dulu: penyedia yang kalah/dibatalkan BUKAN kegagalan sumber (jangan buka circuit breaker).
                abandoned.set(true)
                if (!primaryFuture.isDone) primaryFuture.cancel(true)
                secondaryFuture?.let { if (!it.isDone) it.cancel(true) }
            }
            if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)
        }

        while (idx < activeProviders.size) {
            if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)
            val provider = activeProviders[idx++]
            attempted.add(provider.priority)
            val outcome = fetchFromProvider(provider, animeTitle, episodeNumber)
            val resolved = handleProviderOutcome(
                outcome, cacheKey, qualityOrder, allowBatchFallback, preferCompatibleContainer, attempted, errors, cfHosts
            )
            if (resolved != null) return resolved
        }

        if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)
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
        episodeNumber: Int,
        abandoned: AtomicBoolean? = null
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
            if (isCancellation(e, abandoned)) {
                // Dibatalkan pengguna/hedging: bukan salah sumber. Jangan hukum circuit breaker maupun skor kesehatan.
                Thread.currentThread().interrupt()
                return ProviderFetchOutcome(provider.priority, error = "dibatalkan")
            }
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
        val usable = rankCandidates(outcome.candidates, qualityOrder, allowBatchFallback, preferCompatibleContainer)
        if (usable.isEmpty()) {
            errors[outcome.source] = "hanya arsip batch"
            return null
        }
        val availQualities = usable.map { it.quality }.distinct().sortedByDescending { it.height }
        val resolvedList = raceExtractByQualityOrder(usable, qualityOrder, maxResults = 3)
        if (resolvedList.isNotEmpty()) {
            putRawCache(cacheKey, outcome.candidates, System.currentTimeMillis())
            // Beri tahu lapisan Cloudflare (hint bersama). Tidak boleh memblok/menggagalkan pemutaran.
            val balancedHints = MkvQuality.ALL_DESC.flatMap { q -> usable.filter { it.quality == q }.take(2) }.take(8)
            runCatching { onEpisodeResolved?.invoke(cacheKey, balancedHints.ifEmpty { usable.take(6) }) }
            return CascadeResult.Success(
                selected = resolvedList.first(),
                alternatives = resolvedList.drop(1),
                diagnostics = CascadeDiagnostics(attempted, errors, cfHosts),
                availableQualities = availQualities
            )
        }
        errors[outcome.source] = "${usable.size} link hoster gagal diekstrak/mati"
        return null
    }

    private fun putRawCache(cacheKey: String, candidates: List<RawMkvCandidate>, nowMs: Long) {
        if (rawCache.size > 64) rawCache.clear()
        rawCache[cacheKey] = CachedEpisodeCandidates(nowMs, candidates)
        rawStore?.put(cacheKey, candidates)
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

    private fun raceExtractByQualityOrder(
        candidates: List<RawMkvCandidate>,
        qualityOrder: List<MkvQuality>,
        maxResults: Int = 3
    ): List<ResolvedMkvMedia> {
        val totalDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        for (quality in qualityOrder) {
            if (Thread.currentThread().isInterrupted) break
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(totalDeadlineNs - System.nanoTime())
            if (remainingMs <= 500L) break
            val tier = candidates.filter { it.quality == quality }
            if (tier.isEmpty()) continue
            val tierBudgetMs = remainingMs.coerceAtMost(4_500L)
            val extracted = raceExtractCandidates(tier, maxResults = maxResults, timeoutMs = tierBudgetMs)
            if (extracted.isNotEmpty()) return extracted
        }
        return emptyList()
    }

    private fun raceExtractCandidates(
        candidates: List<RawMkvCandidate>,
        maxResults: Int = 3,
        timeoutMs: Long = 7_000L
    ): List<ResolvedMkvMedia> {
        val fresh = candidates.filterNot { DeadCandidateCache.isDead(it.rawUrl) }
        val batch = (if (fresh.isNotEmpty()) fresh else candidates).take(8)
        if (batch.isEmpty()) return emptyList()
        val cs = ExecutorCompletionService<ResolvedMkvMedia?>(sharedExtractionPool)
        val futures = ArrayList<Future<ResolvedMkvMedia?>>(batch.size)
        val results = mutableListOf<ResolvedMkvMedia>()
        val abandoned = AtomicBoolean(false)
        try {
            for (candidate in batch) {
                futures.add(
                    cs.submit {
                        runCatching {
                            val finalUrl = unshortener.unshorten(candidate.rawUrl, candidate.pageReferer, maxHops = 5)
                            val media = extractorRegistry.extract(finalUrl, candidate.pageReferer)
                            if (media == null) {
                                // Kandidat yang sekadar kalah balapan/dibatalkan BUKAN kandidat mati.
                                if (abandoned.get() || Thread.currentThread().isInterrupted) return@runCatching null
                                DeadCandidateCache.markDead(candidate.rawUrl)
                                EndpointHealthScorer.recordFailure(candidate.hosterLabel)
                                return@runCatching null
                            }
                            if (!media.isArchive && !extractorRegistry.verifyPlayableMedia(media)) {
                                if (abandoned.get() || Thread.currentThread().isInterrupted) return@runCatching null
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
                                isHls = media.isHls,
                                extraHeaders = media.extraHeaders
                            )
                        }.getOrNull()
                    }
                )
            }
            var deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val firstWinGraceNs = TimeUnit.MILLISECONDS.toNanos(120L)
            repeat(batch.size) {
                if (Thread.currentThread().isInterrupted) return@repeat
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return@repeat
                val fut = try {
                    cs.poll(remainingNs, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } ?: return@repeat
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
            abandoned.set(true)
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
        return results
    }

    companion object {
        private const val RAW_CACHE_TTL_MS = 20L * 60L * 1000L
        private const val DISK_RAW_TTL_MS = 6L * 60L * 60L * 1000L
        private const val HEDGE_DELAY_MS = 600L

        private val extractSeq = AtomicInteger(1)
        private val sharedExtractionPool: ExecutorService = Executors.newFixedThreadPool(8) { r ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }, "milkglass-extract-${extractSeq.getAndIncrement()}").apply {
                isDaemon = true
            }
        }

        private val providerSeq = AtomicInteger(1)
        private val sharedProviderPool: ExecutorService = Executors.newFixedThreadPool(8) { r ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                r.run()
            }, "milkglass-provider-${providerSeq.getAndIncrement()}").apply {
                isDaemon = true
            }
        }
    }
}

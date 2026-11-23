package id.my.milkglass.source

import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.extractor.ExtractedDirectMedia
import id.my.milkglass.extractor.LinkUnshortener
import id.my.milkglass.extractor.MkvHosterExtractorRegistry
import id.my.milkglass.net.CloudflareChallengeException
import id.my.milkglass.net.IsolatedHttpClients
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
 * Prioritas Sumber (berlaku per resolusi):
 * Hanya Otakudesu dan YLnime (watermarkTier 0) (watermarkTier 2).
 */
enum class SourcePriority(
    val rank: Int,
    val id: String,
    val defaultBaseUrl: String,
    val watermarkTier: Int
) {
    OTAKUDESU(1, "otakudesu", "https://otakudesu.blog", 2),
    YLNIME(2, "ylnime", "https://ylnime.com", 0)
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
                low.contains("720") || low.contains("mp4hd") || low == "hd" -> P720
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
    val container: MediaContainer = MediaContainer.MKV,
    val isStreamEmbed: Boolean = false
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
    private val sideStoryRegex = Regex("""\b(?:side\s*story|gaiden|spin[\s-]*off|nikki|diaries|coleus|bullet)\b""", RegexOption.IGNORE_CASE)
    private val specialRegex = Regex("""\b(?:special|recap|sp)\b""", RegexOption.IGNORE_CASE)
    private val romanSeasonRegex = Regex("""\b(II|III|IV|V)\b""")
    private val symbolSeason2Regex = Regex("""(?:∬|²)""")
    private val whitespaceRegex = Regex("\\s+")
    private val ordinalSeasonRegex = Regex("""\b(\d+)(?:nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE)
    private val shortSeasonRegex = Regex("""\bs(\d+)\b""", RegexOption.IGNORE_CASE)
    private val roman3Regex = Regex("""\bIII\b""", RegexOption.IGNORE_CASE)
    private val roman2Regex = Regex("""\bII\b""", RegexOption.IGNORE_CASE)
    private val roman4Regex = Regex("""\bIV\b""", RegexOption.IGNORE_CASE)
    private val roman5Regex = Regex("""\bV\b""", RegexOption.IGNORE_CASE)

    private val synonymAliases = mapOf(
        "5 toubun no hanayome" to "Gotoubun no Hanayome",
        "go toubun no hanayome" to "Gotoubun no Hanayome",
        "kanojo okarishimasu" to "Rent a Girlfriend",
        "youkoso jitsuryoku shijou shugi no kyoushitsu e" to "Classroom of the Elite",
        "shingeki no kyojin" to "Attack on Titan",
        "tensei shitara slime datta ken" to "Tensura",
        "dungeon ni deai wo motomeru no wa machigatteiru darou ka" to "Danmachi"
    )

    private val parseCache = ConcurrentHashMap<String, ParsedTitle>()

    fun parse(input: String): ParsedTitle {
        parseCache[input]?.let { return it }
        val p = parseUncached(input)
        if (parseCache.size >= 20_000) parseCache.clear()
        parseCache[input] = p
        return p
    }

    private fun parseUncached(input: String): ParsedTitle {
        val cleanedPlusOva = Regex("""\+\s*(?:ova|oad|special)\b""", RegexOption.IGNORE_CASE).replace(input, " ")
        val noBrackets = brackets.replace(cleanedPlusOva.replace('\u00D7', 'x'), " ")
        var season: Int? = null
        for (p in seasonPatterns) {
            val m = p.find(noBrackets)
            if (m != null) {
                season = m.groupValues[1].toIntOrNull()
                if (season != null) break
            }
        }
        if (season == null) {
            if (symbolSeason2Regex.containsMatchIn(noBrackets)) {
                season = 2
            } else {
                val rm = romanSeasonRegex.find(noBrackets)
                season = when (rm?.groupValues?.getOrNull(1)) {
                    "II" -> 2
                    "III" -> 3
                    "IV" -> 4
                    "V" -> 5
                    else -> null
                }
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
        val normalized = brackets.replace(input.replace('\u00D7', 'x'), " ")
            .replace(symbolSeason2Regex, " season 2 ")
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
    private val parenBracketStripRegex = Regex("""(?:\([^)]*\)|\[[^\]]*\])""")
    private val noiseWordsRegex = Regex(
        """\b(?:movie|gekijouban|film|ova|oad|special|sub|indo|subtitle|indonesia|batch|bd|bluray|uncensored|full|1080p|720p|480p|360p|the|tv)\b""",
        RegexOption.IGNORE_CASE
    )
    private val romajiOuRegex = Regex("""ou""")
    private val romajiUuRegex = Regex("""uu""")
    private val romajiOoRegex = Regex("""oo""")

    /**
     * Lapis 1: Normalisasi kanonis + fonetik/Romaji (tahan simbol, tag noise, ejaan UK/US, & Romaji).
     */
    internal fun normalizePhoneticTokens(raw: String): List<String> {
        val cleaned = parenBracketStripRegex.replace(raw.replace('\u00D7', 'x'), " ")
            .lowercase(Locale.ROOT)
            .replace("judgement", "judgment")
            .replace("colour", "color")
            .replace("theatre", "theater")
        val withoutNoise = noiseWordsRegex.replace(cleaned, " ")
        val words = nonWord.replace(withoutNoise, " ")
            .split(" ")
            .map { w ->
                var t = w.trim()
                if (t.length >= 4) {
                    t = romajiOuRegex.replace(t, "o")
                    t = romajiUuRegex.replace(t, "u")
                    t = romajiOoRegex.replace(t, "o")
                }
                t
            }
            .filter { it.isNotEmpty() && !trailingYearRegex.matches(it) && (it.length >= 2 || it[0].isDigit()) }
        return words
    }

    internal fun extractDistinctiveSubtitle(raw: String): String {
        val noTags = parenBracketStripRegex.replace(raw, " ").trim()
        val after = when {
            noTags.contains(":") -> noTags.substringAfter(":").trim()
            noTags.contains(" - ") -> noTags.substringAfter(" - ").trim()
            else -> ""
        }
        if (after.isBlank()) return ""
        val subCompact = normalizePhoneticTokens(after).joinToString("")
        return if (subCompact.length >= 4) subCompact else ""
    }

    /**
     * Lapis 2a: Kemiripan Bigram Karakter (Dice) — simetris, tahan typo 2 arah & urutan kata tertukar.
     */
    internal fun bigramDice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return if (a == b && a.isNotEmpty()) 1.0 else 0.0
        val counts = HashMap<String, Int>(a.length)
        for (i in 0 until a.length - 1) {
            val bg = a.substring(i, i + 2)
            counts[bg] = (counts[bg] ?: 0) + 1
        }
        var inter = 0
        for (i in 0 until b.length - 1) {
            val bg = b.substring(i, i + 2)
            val rem = counts[bg] ?: 0
            if (rem > 0) {
                inter++
                counts[bg] = rem - 1
            }
        }
        return (2.0 * inter) / ((a.length - 1) + (b.length - 1)).toDouble()
    }

    /**
     * Lapis 2b: Rasio subsequence berurutan (mengabaikan spasi, simbol, dan teks tambahan).
     */
    internal fun orderedSubsequenceRatio(needle: String, haystack: String): Double {
        if (needle.isEmpty() || haystack.isEmpty()) return 0.0
        var matched = 0
        var hIdx = 0
        for (ch in needle) {
            while (hIdx < haystack.length && haystack[hIdx] != ch) hIdx++
            if (hIdx < haystack.length) {
                matched++
                hIdx++
            }
        }
        return matched.toDouble() / needle.length.toDouble()
    }

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
        val qCompact = pq.canonicalBase.replace(" ", "")
        val cCompact = pc.canonicalBase.replace(" ", "")
        val qNoYear = trailingYearRegex.replace(qCompact, "")
        val cNoYear = trailingYearRegex.replace(cCompact, "")

        val qCoreTokens = normalizePhoneticTokens(query).toSet()
        val cCoreTokens = normalizePhoneticTokens(candidate).toSet()
        val coreTokenJaccard = if (qCoreTokens.isNotEmpty() && cCoreTokens.isNotEmpty()) {
            val cInter = qCoreTokens.count { it in cCoreTokens }.toDouble()
            cInter / (qCoreTokens.size + cCoreTokens.size - cInter)
        } else 0.0
        val qCore = qCoreTokens.sorted().joinToString("")
        val cCore = cCoreTokens.sorted().joinToString("")
        val qCoreOrdered = normalizePhoneticTokens(query).joinToString("")
        val cCoreOrdered = normalizePhoneticTokens(candidate).joinToString("")

        val dice = if (qCoreOrdered.length >= 4 && cCoreOrdered.length >= 4) {
            maxOf(bigramDice(qCoreOrdered, cCoreOrdered), bigramDice(qCore, cCore))
        } else 0.0

        val subseqRatio = if (qCoreOrdered.length >= 5 && cCoreOrdered.length >= 5) {
            val shorter = if (qCoreOrdered.length <= cCoreOrdered.length) qCoreOrdered else cCoreOrdered
            val longer = if (qCoreOrdered.length <= cCoreOrdered.length) cCoreOrdered else qCoreOrdered
            val lenRatio = shorter.length.toDouble() / longer.length.toDouble()
            val forward = orderedSubsequenceRatio(shorter, longer)
            if (forward >= 0.88 && lenRatio >= 0.58) forward else 0.0
        } else 0.0

        // Lapis 3: Kunci Identitas Subjudul/Arc (mencegah Dead or Bullet tertukar Nightmare or Queen)
        val qSub = extractDistinctiveSubtitle(query)
        val cSub = extractDistinctiveSubtitle(candidate)
        val subtitleConflict = if (qSub.length >= 4 && cSub.length >= 4 && coreTokenJaccard < 0.80) {
            val shortSub = if (qSub.length <= cSub.length) qSub else cSub
            val longSub = if (qSub.length <= cSub.length) cSub else qSub
            val subLenRatio = shortSub.length.toDouble() / longSub.length.toDouble()
            val subSubseq = if (subLenRatio >= 0.55) orderedSubsequenceRatio(shortSub, longSub) else 0.0
            val subSim = maxOf(bigramDice(qSub, cSub), subSubseq)
            subSim < 0.45
        } else false

        if (qCompact.length >= 4 && cCompact.length >= 4 && !subtitleConflict) {
            when {
                qCompact == cCompact || (qNoYear.length >= 4 && qNoYear == cNoYear) ||
                    (qCoreOrdered.length >= 5 && qCoreOrdered == cCoreOrdered) ->
                    sc = maxOf(sc, 0.95)
                coreTokenJaccard >= 0.85 || dice >= 0.84 ->
                    sc = maxOf(sc, 0.86)
                cCompact.contains(qCompact) || qCompact.contains(cCompact) ||
                    (qNoYear.length >= 4 && cNoYear.length >= 4 && (cNoYear.contains(qNoYear) || qNoYear.contains(cNoYear))) ->
                    sc = maxOf(sc, 0.72)
                subseqRatio >= 0.88 ->
                    sc = maxOf(sc, 0.78)
            }
        }
        if (subtitleConflict) {
            sc -= 0.90
        }
        // Lapis 4: Format Override jika inti judul/subjudul cocok kuat dan tidak ada konflik subjudul
        val strongCoreMatch = !subtitleConflict && qCoreOrdered.length >= 10 && cCoreOrdered.length >= 10 &&
            (qCoreOrdered == cCoreOrdered || coreTokenJaccard >= 0.85 || dice >= 0.84 || subseqRatio >= 0.90)
        if (pq.formatKind != pc.formatKind && !strongCoreMatch) {
            sc -= 0.95
        }
        sc += StreamingFirstGuards.seasonAndPartDelta(pq, pc)
        return sc.coerceAtLeast(0.0)
    }

    fun matchesEpisode(text: String, href: String, episodeNumber: Int): Boolean {
        val trimmed = text.trim()
        if (trimmed.toIntOrNull() == episodeNumber) return true
        if (SeriesEpisodeMapCache.extractEpisodeNumber(trimmed, href) == episodeNumber) return true
        val epRegex = Regex("""(?:episode|eps|ep)[\s\-_]*0*($episodeNumber)(?!(?:\.\d|\d))""", RegexOption.IGNORE_CASE)
        return epRegex.containsMatchIn(trimmed) || epRegex.containsMatchIn(href)
    }

    fun searchQueries(animeTitle: String): List<String> {
        val withoutParen = animeTitle.replace(Regex("""\([^)]*\)"""), " ")
        val parenInner = Regex("""\(([^)]+)\)""").find(animeTitle)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val p = parse(withoutParen)
        val cleanFull = nonWord.replace(brackets.replace(withoutParen, " "), " ")
            .replace(whitespaceRegex, " ")
            .trim()
        val beforeColon = nonWord.replace(brackets.replace(withoutParen.substringBefore(":"), " "), " ")
            .replace(whitespaceRegex, " ")
            .trim()
        val afterColon = if (withoutParen.contains(":")) {
            nonWord.replace(brackets.replace(withoutParen.substringAfter(":"), " "), " ")
                .replace(whitespaceRegex, " ")
                .trim()
        } else ""
        val beforeDash = if (withoutParen.contains(" - ")) {
            nonWord.replace(brackets.replace(withoutParen.substringBefore(" - "), " "), " ")
                .replace(whitespaceRegex, " ")
                .trim()
        } else ""
        val afterDash = if (withoutParen.contains(" - ")) {
            nonWord.replace(brackets.replace(withoutParen.substringAfter(" - "), " "), " ")
                .replace(whitespaceRegex, " ")
                .trim()
        } else ""
        val base = p.canonicalBase
        val out = mutableListOf<String>()
        if (p.season != null && base.isNotBlank()) {
            out.add("$base Season ${p.season}")
            out.add("$base S${p.season}")
            out.add("$base ${p.season}")
        }
        for ((prefix, alias) in synonymAliases) {
            if (base.startsWith(prefix)) {
                if (p.season != null) {
                    out.add("$alias Season ${p.season}")
                    out.add("$alias S${p.season}")
                }
                out.add(alias)
            }
        }
        val noYear = cleanFull.replace(Regex("""\b(?:19|20)\d{2}\b"""), " ").replace(whitespaceRegex, " ").trim()
        if (noYear.isNotBlank() && noYear != cleanFull) out.add(noYear)
        if (cleanFull.isNotBlank()) out.add(cleanFull)
        if (parenInner.length >= 3 && !parenInner.all { it.isDigit() }) out.add(parenInner)
        if (beforeColon.isNotBlank()) out.add(beforeColon)
        if (afterColon.length >= 4) out.add(afterColon)
        if (beforeDash.isNotBlank()) out.add(beforeDash)
        if (afterDash.length >= 4) out.add(afterDash)
        if (base.isNotBlank()) out.add(base)
        val noMovieNum = beforeColon.replace(Regex("""\bmovie\s*\d+\b""", RegexOption.IGNORE_CASE), "Movie")
            .replace(whitespaceRegex, " ").trim()
        if (noMovieNum.isNotBlank() && noMovieNum != beforeColon) out.add(noMovieNum)
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

    private val EP_TEXT_REGEX = Regex("(?:episode|eps?|ep)\\s*0*(\\d{1,4})(?!(?:\\.\\d|\\d))", RegexOption.IGNORE_CASE)
    private val EP_HREF_REGEX = Regex("(?:episode|eps?|ep)[-_\\s]*0*(\\d{1,4})(?!(?:\\.\\d|\\d))", RegexOption.IGNORE_CASE)
    private val YLNIME_EP_HREF_REGEX = Regex("[?&]episode=[a-z0-9_-]+-0*(\\d{1,4})(?:[&#]|$)", RegexOption.IGNORE_CASE)
    private val MOVIE_EP_TEXT_REGEX = Regex("^(?:episode\\s+)?(?:movie|film|ova|special)$", RegexOption.IGNORE_CASE)
    private val OVA_SPECIAL_NUM_TEXT_REGEX = Regex("\\b(?:ova|oad|special)\\s*0*(\\d{1,3})(?!(?:\\.\\d|\\d))", RegexOption.IGNORE_CASE)
    private val OVA_SPECIAL_NUM_HREF_REGEX = Regex("(?:ova|oad|special)[-_]+0*(\\d{1,3})(?!(?:\\.\\d|\\d))", RegexOption.IGNORE_CASE)
    private val BONUS_EP_REGEX = Regex("\\b(?:ova|oad|special|recap|bonus|preview)\\b", RegexOption.IGNORE_CASE)
    private val SLUG_EP_REGEX = Regex("-ep-(\\d+)$", RegexOption.IGNORE_CASE)

    fun isBonusOrSpecialLink(text: String, href: String): Boolean =
        BONUS_EP_REGEX.containsMatchIn(text) || BONUS_EP_REGEX.containsMatchIn(href)

    fun extractEpisodeNumberFromSlug(slug: String): Int =
        SLUG_EP_REGEX.find(slug)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

    fun extractEpisodeNumber(text: String, href: String): Int? {
        val fromText = EP_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromText != null) return fromText
        val fromHref = EP_HREF_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromHref != null) return fromHref
        val fromYlnime = YLNIME_EP_HREF_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromYlnime != null) return fromYlnime
        val fromOvaText = OVA_SPECIAL_NUM_TEXT_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromOvaText != null) return fromOvaText
        val fromOvaHref = OVA_SPECIAL_NUM_HREF_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (fromOvaHref != null) return fromOvaHref
        if (MOVIE_EP_TEXT_REGEX.matches(text.trim())) return 1
        return null
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
    private val embedSniffer: ((url: String, referer: String?) -> ExtractedDirectMedia?)? = null,
    private val providers: List<MkvSourceProvider> = listOf(
        OtakudesuSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.OTAKUDESU) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher),
        YlnimeSourceProvider(clients, slugCache = slugCache, baseUrlProvider = { baseUrlProvider(SourcePriority.YLNIME) }, indexLookup = indexLookup, remoteHtmlFetcher = remoteHtmlFetcher)
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

    private data class ProviderResolvedOutcome(
        val source: SourcePriority,
        val selected: ResolvedMkvMedia? = null,
        val alternatives: List<ResolvedMkvMedia> = emptyList(),
        val availableQualities: List<MkvQuality> = emptyList(),
        val cfHost: String? = null,
        val error: String? = null
    )

    private val rawCache = ConcurrentHashMap<String, CachedEpisodeCandidates>()

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

        val active = adaptiveProviderOrder().take(2).filterNot { SourceCircuitBreaker.isOpen(it.priority) }
        if (active.isEmpty()) return false
        val queue = LinkedBlockingQueue<ProviderFetchOutcome>(active.size)
        val abandoned = AtomicBoolean(false)
        val futures = active.map { provider ->
            sharedProviderPool.submit {
                val outcome = runCatching { fetchFromProvider(provider, animeTitle, episodeNumber, abandoned) }
                    .getOrElse { ProviderFetchOutcome(provider.priority, error = it.message) }
                queue.offer(outcome)
            }
        }
        try {
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
            repeat(active.size) {
                val rem = deadlineNs - System.nanoTime()
                if (rem <= 0L) return@repeat
                val done = pollKeepingInterrupt(queue, rem) ?: return@repeat
                if (done.candidates.isNotEmpty()) {
                    SourceCircuitBreaker.recordSuccess(done.source)
                    putRawCache(cacheKey, done.candidates, System.currentTimeMillis())
                    return true
                }
            }
            return false
        } finally {
            abandoned.set(true)
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    fun verifyDirect(list: List<ResolvedMkvMedia>): List<ResolvedMkvMedia> {
        if (list.isEmpty()) return emptyList()
        val batch = list.take(4)
        val cs = ExecutorCompletionService<ResolvedMkvMedia?>(sharedExtractionPool)
        val futures = batch.map { m ->
            cs.submit {
                val ok = runCatching {
                    extractorRegistry.verifyPlayableMedia(
                        ExtractedDirectMedia(
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
        val verified = mutableListOf<ResolvedMkvMedia>()
        var deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_800L)
        val firstWinGraceNs = TimeUnit.MILLISECONDS.toNanos(120L)
        try {
            repeat(futures.size) {
                if (Thread.currentThread().isInterrupted) return@repeat
                val rem = deadlineNs - System.nanoTime()
                if (rem <= 0L) return@repeat
                val fut = try {
                    cs.poll(rem, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } ?: return@repeat
                val hit = runCatching { fut.get() }.getOrNull()
                if (hit != null) {
                    verified.add(hit)
                    // Prinsip Zero-Hostage: begitu 1 stream lolos verifikasi, langsung kembalikan tanpa menyandera pemutar
                    return@repeat
                }
            }
            return verified
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
    }

    fun resolveFromRaw(
        raw: List<RawMkvCandidate>,
        preferredQuality: MkvQuality,
        preferCompatibleContainer: Boolean,
        preferStreamingEmbeds: Boolean = true
    ): List<ResolvedMkvMedia> {
        if (raw.isEmpty()) return emptyList()
        val qualityOrder = listOf(preferredQuality) + MkvQuality.ALL_DESC.filterNot { it == preferredQuality }
        val usable = rankCandidates(raw, qualityOrder, false, preferCompatibleContainer, preferStreamingEmbeds)
        if (usable.isEmpty()) return emptyList()
        return raceExtractByQualityOrder(usable, qualityOrder, maxResults = 3, preferStreamingEmbeds = preferStreamingEmbeds)
    }

    private fun fetchAndResolveProvider(
        provider: MkvSourceProvider,
        animeTitle: String,
        episodeNumber: Int,
        cacheKey: String,
        qualityOrder: List<MkvQuality>,
        allowBatchFallback: Boolean,
        preferCompatibleContainer: Boolean,
        preferStreamingEmbeds: Boolean,
        abandoned: AtomicBoolean? = null
    ): ProviderResolvedOutcome {
        val fetched = fetchFromProvider(provider, animeTitle, episodeNumber, abandoned)
        if (fetched.error != null) {
            return ProviderResolvedOutcome(provider.priority, cfHost = fetched.cfHost, error = fetched.error)
        }
        if (fetched.candidates.isEmpty()) {
            return ProviderResolvedOutcome(provider.priority, cfHost = fetched.cfHost, error = "judul/episode tidak ditemukan")
        }
        SourceCircuitBreaker.recordSuccess(provider.priority)
        val usable = rankCandidates(fetched.candidates, qualityOrder, allowBatchFallback, preferCompatibleContainer, preferStreamingEmbeds)
        if (usable.isEmpty()) {
            return ProviderResolvedOutcome(provider.priority, cfHost = fetched.cfHost, error = "hanya arsip batch")
        }
        val availQualities = usable.map { it.quality }.distinct().sortedByDescending { it.height }
        val resolvedList = raceExtractByQualityOrder(usable, qualityOrder, maxResults = 3, preferStreamingEmbeds = preferStreamingEmbeds)
        if (resolvedList.isNotEmpty()) {
            putRawCache(cacheKey, fetched.candidates, System.currentTimeMillis())
            val balancedHints = MkvQuality.ALL_DESC.flatMap { q -> usable.filter { it.quality == q }.take(2) }.take(8)
            runCatching { onEpisodeResolved?.invoke(cacheKey, balancedHints.ifEmpty { usable.take(6) }) }
            return ProviderResolvedOutcome(
                source = provider.priority,
                selected = resolvedList.first(),
                alternatives = resolvedList.drop(1),
                availableQualities = availQualities,
                cfHost = fetched.cfHost
            )
        }
        return ProviderResolvedOutcome(
            source = provider.priority,
            cfHost = fetched.cfHost,
            error = "${usable.size} link hoster gagal diekstrak/mati"
        )
    }

    private fun consumeResolvedOutcome(
        done: ProviderResolvedOutcome,
        attempted: List<SourcePriority>,
        errors: MutableMap<SourcePriority, String>,
        cfHosts: MutableSet<String>
    ): CascadeResult.Success? {
        if (done.cfHost != null) cfHosts.add(done.cfHost)
        val sel = done.selected
        if (sel != null) {
            return CascadeResult.Success(
                selected = sel,
                alternatives = done.alternatives,
                diagnostics = CascadeDiagnostics(attempted, errors, cfHosts),
                availableQualities = done.availableQualities
            )
        }
        if (done.error != null) {
            errors[done.source] = done.error
        }
        return null
    }

    fun resolve(
        animeTitle: String,
        episodeNumber: Int,
        preferredQuality: MkvQuality = MkvQuality.P1080,
        allowBatchFallback: Boolean = false,
        preferCompatibleContainer: Boolean = false,
        preferStreamingEmbeds: Boolean = true
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
            val usableCached = rankCandidates(cachedRaw, qualityOrder, allowBatchFallback, preferCompatibleContainer, preferStreamingEmbeds)
            val cachedQualities = usableCached.map { it.quality }.distinct().sortedByDescending { it.height }
            val fastResolved = raceExtractByQualityOrder(usableCached, qualityOrder, maxResults = 3, preferStreamingEmbeds = preferStreamingEmbeds)
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
            // Optimasi #1: Balapkan Otakudesu & YLnime secara paralel penuh sejak t=0ms (tanpa jeda hedge),
            // first-playable-wins langsung membatalkan provider lainnya.
            val fanOutCount = minOf(activeProviders.size, 2)
            val raceQueue = LinkedBlockingQueue<ProviderResolvedOutcome>(fanOutCount)
            val abandoned = AtomicBoolean(false)
            val futures = ArrayList<Future<*>>(fanOutCount)
            for (i in 0 until fanOutCount) {
                val p = activeProviders[i]
                attempted.add(p.priority)
                futures.add(
                    sharedProviderPool.submit {
                        val outcome = runCatching {
                            fetchAndResolveProvider(
                                p, animeTitle, episodeNumber, cacheKey,
                                qualityOrder, allowBatchFallback, preferCompatibleContainer, preferStreamingEmbeds, abandoned
                            )
                        }.getOrElse { e ->
                            ProviderResolvedOutcome(p.priority, error = e.message ?: e::class.java.simpleName)
                        }
                        raceQueue.offer(outcome)
                    }
                )
            }
            try {
                val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(13L)
                repeat(fanOutCount) {
                    val remainingNs = deadlineNs - System.nanoTime()
                    if (remainingNs <= 0L) return@repeat
                    val done = pollKeepingInterrupt(raceQueue, remainingNs) ?: return@repeat
                    val resolved = consumeResolvedOutcome(done, attempted, errors, cfHosts)
                    if (resolved != null) return resolved
                }
                idx = fanOutCount
            } finally {
                abandoned.set(true)
                futures.forEach { if (!it.isDone) it.cancel(true) }
            }
            if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)
        }

        while (idx < activeProviders.size) {
            if (Thread.currentThread().isInterrupted) return cancelledFailure(attempted, errors, cfHosts)
            val provider = activeProviders[idx++]
            attempted.add(provider.priority)
            val outcome = fetchAndResolveProvider(
                provider, animeTitle, episodeNumber, cacheKey,
                qualityOrder, allowBatchFallback, preferCompatibleContainer, preferStreamingEmbeds
            )
            val resolved = consumeResolvedOutcome(outcome, attempted, errors, cfHosts)
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
                Thread.currentThread().interrupt()
                return ProviderFetchOutcome(provider.priority, error = "dibatalkan")
            }
            SourceCircuitBreaker.recordFailure(provider.priority)
            EndpointHealthScorer.recordSourceFailure(provider.priority.id)
            ProviderFetchOutcome(provider.priority, error = e.message ?: e::class.java.simpleName)
        }
    }

    private fun putRawCache(cacheKey: String, candidates: List<RawMkvCandidate>, nowMs: Long) {
        if (rawCache.size > 64) rawCache.clear()
        rawCache[cacheKey] = CachedEpisodeCandidates(nowMs, candidates)
        rawStore?.put(cacheKey, candidates)
    }

    internal fun rankCandidates(
        candidates: List<RawMkvCandidate>,
        qualityOrder: List<MkvQuality>,
        allowBatchFallback: Boolean,
        preferCompatibleContainer: Boolean,
        preferStreamingEmbeds: Boolean = true
    ): List<RawMkvCandidate> = StreamingFirstGuards.rankForPlayback(
        candidates = candidates,
        qualityOrder = qualityOrder,
        allowBatchFallback = allowBatchFallback,
        preferCompatibleContainer = preferCompatibleContainer,
        preferStreamingEmbeds = preferStreamingEmbeds
    )

    /**
     * Arsitektur 2-Tahap Ketat:
     * - Tahap 1 (Embed-First Eksklusif): HANYA kandidat stream embed langsung yang aktif dan diuji lebih dulu.
     *   Hoster download tidak disentuh sama sekali selama Tahap 1.
     * - Tahap 2 (Fallback 4 Hoster Cepat & Sumber 1080p): Hanya aktif otomatis jika Tahap 1 gagal
     *   atau jika 1080p diminta namun tidak tersedia di embed. Hanya memakai 4 hoster cepat
     *   (Pixeldrain, GoFile, Buzzheavier, Filedon/Odfiles).
     */
    private fun raceExtractByQualityOrder(
        candidates: List<RawMkvCandidate>,
        qualityOrder: List<MkvQuality>,
        maxResults: Int = 3,
        preferStreamingEmbeds: Boolean = true
    ): List<ResolvedMkvMedia> {
        val totalDeadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(11L)
        val embeds = candidates.filter { it.isStreamEmbed }.distinctBy { it.rawUrl }
        val wants1080p = qualityOrder.firstOrNull() == MkvQuality.P1080
        val embedHas1080p = embeds.any { it.quality == MkvQuality.P1080 }

        // Tahap 1: HANYA Embed-First yang aktif lebih dulu (kecuali user khusus memilih 1080p dan embed tidak punya 1080p)
        if (preferStreamingEmbeds && embeds.isNotEmpty() && (!wants1080p || embedHas1080p)) {
            val embedBatch = if (wants1080p) {
                embeds.sortedByDescending { it.quality == MkvQuality.P1080 }.take(4)
            } else {
                embeds.take(4)
            }
            val embedWin = raceExtractCandidates(embedBatch, maxResults = maxResults, timeoutMs = 6_800L)
            if (embedWin.isNotEmpty()) return embedWin
        }

        // Tahap 2: Fallback otomatis ke 4 hoster cepat (Pixeldrain, GoFile, Buzzheavier, Filedon/Odfiles)
        val fastHosters = candidates
            .filter { !it.isStreamEmbed && id.my.milkglass.extractor.HosterFilter.isFastFallbackHoster(it.hosterLabel, it.rawUrl) }
            .distinctBy { it.rawUrl }

        for (quality in qualityOrder) {
            if (Thread.currentThread().isInterrupted) break
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(totalDeadlineNs - System.nanoTime())
            if (remainingMs <= 500L) break
            val tier = fastHosters
                .filter { it.quality == quality }
                .sortedBy { id.my.milkglass.extractor.HosterFilter.rank(it.hosterLabel, it.rawUrl) }
                .take(4)
            if (tier.isEmpty()) continue
            val tierBudgetMs = remainingMs.coerceAtMost(2_200L)
            val extracted = raceExtractCandidates(tier, maxResults = maxResults, timeoutMs = tierBudgetMs)
            if (extracted.isNotEmpty()) return extracted
        }

        // Jika 1080p tidak ketemu di 4 hoster cepat dan embed belum dicoba, coba embed sebagai jaring pengaman terakhir
        if (preferStreamingEmbeds && embeds.isNotEmpty() && wants1080p && !embedHas1080p) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(totalDeadlineNs - System.nanoTime())
            if (remainingMs > 500L) {
                val embedFallback = raceExtractCandidates(embeds.take(4), maxResults = maxResults, timeoutMs = remainingMs.coerceAtMost(6_800L))
                if (embedFallback.isNotEmpty()) return embedFallback
            }
        }
        return emptyList()
    }

    private fun toResolvedMedia(candidate: RawMkvCandidate, media: ExtractedDirectMedia): ResolvedMkvMedia {
        val detectedContainer = when {
            media.isHls -> MediaContainer.HLS
            media.directUrl.contains(".mp4", ignoreCase = true) -> MediaContainer.MP4
            media.directUrl.contains(".mkv", ignoreCase = true) -> MediaContainer.MKV
            else -> candidate.container
        }
        return ResolvedMkvMedia(
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
    }

    private fun raceExtractCandidates(
        candidates: List<RawMkvCandidate>,
        maxResults: Int = 3,
        timeoutMs: Long = 6_500L
    ): List<ResolvedMkvMedia> {
        val fresh = candidates.filterNot { DeadCandidateCache.isDead(it.rawUrl) }
        val pool = if (fresh.isNotEmpty()) fresh else candidates
        val batch = StreamingFirstGuards.selectExtractionBatch(pool)
        if (batch.isEmpty()) return emptyList()
        val cs = ExecutorCompletionService<ResolvedMkvMedia?>(sharedExtractionPool)
        val futures = ArrayList<Future<ResolvedMkvMedia?>>(batch.size + 1)
        val results = mutableListOf<ResolvedMkvMedia>()
        val abandoned = AtomicBoolean(false)
        try {
            val topEmbed = batch.firstOrNull { it.isStreamEmbed }
            val sniffer = embedSniffer
            if (topEmbed != null && sniffer != null) {
                val deferSnifferMs = if (StreamingFirstGuards.shouldDeferWebViewSniffer(batch)) 900L else 0L
                futures.add(
                    cs.submit {
                        runCatching {
                            if (deferSnifferMs > 0L) {
                                val endNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deferSnifferMs)
                                while (!abandoned.get() && !Thread.currentThread().isInterrupted && System.nanoTime() < endNs) {
                                    Thread.sleep(30L)
                                }
                            }
                            if (abandoned.get() || Thread.currentThread().isInterrupted) return@runCatching null
                            val sniffed = sniffer.invoke(topEmbed.rawUrl, topEmbed.pageReferer) ?: return@runCatching null
                            // Wajib verifikasi untuk URL non-CDN agar halaman HTML/error tidak pernah lolos ke ExoPlayer,
                            // tetapi lewati TCP probe kedua untuk endpoint stream biner CDN terpercaya (googlevideo/blogger/odcloud/pixeldrain API)
                            if (!sniffed.isArchive &&
                                !id.my.milkglass.extractor.EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(sniffed.directUrl) &&
                                !extractorRegistry.verifyPlayableMedia(sniffed)
                            ) {
                                return@runCatching null
                            }
                            EndpointHealthScorer.recordSuccess(sniffed.hoster)
                            toResolvedMedia(topEmbed, sniffed)
                        }.getOrNull()
                    }
                )
            }
            for (candidate in batch) {
                futures.add(
                    cs.submit {
                        runCatching {
                            val finalUrl = if (candidate.isStreamEmbed) {
                                candidate.rawUrl
                            } else {
                                unshortener.unshorten(candidate.rawUrl, candidate.pageReferer, maxHops = 5)
                            }
                            var media = extractorRegistry.extract(finalUrl, candidate.pageReferer)
                            if (media == null && candidate.isStreamEmbed && !abandoned.get() && !Thread.currentThread().isInterrupted) {
                                val nested = StreamingFirstGuards.unwrapNestedEmbedUrl(clients, finalUrl, candidate.pageReferer)
                                if (!nested.isNullOrBlank()) {
                                    media = extractorRegistry.extract(nested, finalUrl)
                                }
                            }
                            if (media == null) {
                                if (abandoned.get() || Thread.currentThread().isInterrupted) return@runCatching null
                                if (!candidate.isStreamEmbed) {
                                    DeadCandidateCache.markDead(candidate.rawUrl)
                                    EndpointHealthScorer.recordFailure(candidate.hosterLabel)
                                }
                                return@runCatching null
                            }
                            if (!media.isArchive &&
                                !id.my.milkglass.extractor.EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(media.directUrl) &&
                                !extractorRegistry.verifyPlayableMedia(media)
                            ) {
                                if (abandoned.get() || Thread.currentThread().isInterrupted) return@runCatching null
                                DeadCandidateCache.markDead(candidate.rawUrl)
                                EndpointHealthScorer.recordFailure(media.hoster)
                                return@runCatching null
                            }
                            EndpointHealthScorer.recordSuccess(media.hoster)
                            toResolvedMedia(candidate, media)
                        }.getOrNull()
                    }
                )
            }
            var deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val firstWinGraceNs = 0L
            repeat(futures.size) {
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
        private const val HEDGE_DELAY_MS = 120L

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

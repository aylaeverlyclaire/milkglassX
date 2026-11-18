package id.my.milkglass.source

import id.my.milkglass.extractor.ExtractedDirectMedia
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

enum class SourcePriority(val rank: Int, val id: String, val defaultBaseUrl: String) {
    OTAKUDESU(1, "otakudesu", "https://otakudesu.blog"),
    SAMEHADAKU(2, "samehadaku", "https://v2.samehadaku.how"),
    OPLOVERZ(3, "oploverz", "https://oploverz.ch"),
    KUSONIME(4, "kusonime", "https://kusonime.com")
}

enum class MkvQuality(val label: String, val height: Int) {
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480);

    companion object {
        val ALL_DESC: List<MkvQuality> = listOf(P1080, P720, P480)

        fun fromText(text: String): MkvQuality? {
            val low = text.lowercase(Locale.ROOT).trim()
            return when {
                low.contains("1080") || low.contains("fullhd") || low == "fhd" -> P1080
                low.contains("720") || low == "hd" -> P720
                low.contains("480") || low == "mini" -> P480
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
    val isBatchArchive: Boolean = false
)

data class ResolvedMkvMedia(
    val source: SourcePriority,
    val quality: MkvQuality,
    val hoster: String,
    val directUrl: String,
    val referer: String?,
    val supportsRange: Boolean,
    val sizeLabel: String?,
    val isBatchArchive: Boolean
)

data class CascadeDiagnostics(
    val attemptedSources: List<SourcePriority>,
    val errors: Map<SourcePriority, String>,
    val cloudflareBlockedHosts: Set<String>
)

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

internal object TitleMatcher {
    private val nonWord = Regex("[^a-z0-9\\s]+")
    private val stopWords = setOf("sub", "indo", "subtitle", "indonesia", "batch", "bd", "episode", "eps")

    fun tokenize(input: String): Set<String> {
        return nonWord.replace(input.lowercase(Locale.ROOT), " ")
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stopWords }
            .toSet()
    }

    fun score(query: String, candidate: String): Double {
        val q = tokenize(query)
        val c = tokenize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        val inter = q.intersect(c).size.toDouble()
        val union = q.union(c).size.toDouble()
        val containsBoost = if (candidate.lowercase(Locale.ROOT).contains(query.lowercase(Locale.ROOT))) 0.25 else 0.0
        return (inter / union) + containsBoost
    }

    fun matchesEpisode(text: String, href: String, episodeNumber: Int): Boolean {
        val epRegex = Regex("""(?:episode|eps|ep)[\s\-_]*0*($episodeNumber)(?!\d)""", RegexOption.IGNORE_CASE)
        return epRegex.containsMatchIn(text) || epRegex.containsMatchIn(href)
    }

    fun isMkvBlock(headerText: String, rowText: String): Boolean {
        val combined = "${headerText.lowercase(Locale.ROOT)} ${rowText.lowercase(Locale.ROOT)}"
        if (combined.contains("mp4") && !combined.contains("mkv")) return false
        return combined.contains("mkv") || combined.contains("x265") || combined.contains("hevc")
    }
}

abstract class BaseHtmlMkvProvider(
    protected val clients: IsolatedHttpClients,
    protected var baseUrl: String
) : MkvSourceProvider {

    protected fun fetchDoc(url: String, referer: String? = null): Document {
        val req = Request.Builder().url(url).apply {
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        clients.sourceClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} dari $url")
            }
            val finalUrl = resp.request.url.toString()
            val html = resp.body?.string().orEmpty()
            return Jsoup.parse(html, finalUrl)
        }
    }

    protected fun encodeQuery(q: String): String = URLEncoder.encode(q, "UTF-8")
}

class OtakudesuSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.OTAKUDESU.defaultBaseUrl
) : BaseHtmlMkvProvider(clients, baseUrl) {

    override val priority: SourcePriority = SourcePriority.OTAKUDESU

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val searchUrl = "$baseUrl/?s=${encodeQuery(animeTitle)}&post_type=anime"
        val searchDoc = fetchDoc(searchUrl, "$baseUrl/")
        val bestAnimeUrl = searchDoc.select("ul.chivsrc > li")
            .mapNotNull { li ->
                val a = li.selectFirst("h2 > a[href]") ?: return@mapNotNull null
                val title = a.text().trim()
                val href = a.absUrl("href")
                if (href.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
            }
            .maxByOrNull { it.first }
            ?.takeIf { it.first >= 0.25 }
            ?.second ?: return emptyList()

        val animeDoc = fetchDoc(bestAnimeUrl, searchUrl)
        val epUrl = animeDoc.select(".episodelist ul li a[href]")
            .firstOrNull { a ->
                val href = a.absUrl("href")
                href.contains("/episode/") && TitleMatcher.matchesEpisode(a.text(), href, episodeNumber)
            }?.absUrl("href") ?: return emptyList()

        val epDoc = fetchDoc(epUrl, bestAnimeUrl)
        val results = mutableListOf<RawMkvCandidate>()

        epDoc.select("div.download ul").forEach { ul ->
            val blockHeader = ul.previousElementSibling()?.text().orEmpty()
            ul.select("li").forEach { li ->
                val strongText = li.selectFirst("strong")?.text().orEmpty()
                if (!TitleMatcher.isMkvBlock(blockHeader, strongText)) return@forEach
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
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
                                sizeLabel = sizeLabel
                            )
                        )
                    }
                }
            }
        }
        return results
    }
}

class SamehadakuSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.SAMEHADAKU.defaultBaseUrl
) : BaseHtmlMkvProvider(clients, baseUrl) {

    override val priority: SourcePriority = SourcePriority.SAMEHADAKU

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val searchUrl = "$baseUrl/?s=${encodeQuery(animeTitle)}"
        val searchDoc = fetchDoc(searchUrl, "$baseUrl/")
        val bestAnimeUrl = searchDoc.select("div.animposx a[href], article.animepost div.animposx a[href], .animepost div.animposx a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
            }
            .maxByOrNull { it.first }
            ?.takeIf { it.first >= 0.25 }
            ?.second ?: return emptyList()

        val animeDoc = fetchDoc(bestAnimeUrl, searchUrl)
        val epUrl = animeDoc.select("div.lstepsiode.listeps ul li a[href], .listeps ul li a[href], .lstepsiode ul li a[href], .epsleft a[href], .episode-lainnya ul li a[href]")
            .firstOrNull { a ->
                TitleMatcher.matchesEpisode(a.text(), a.absUrl("href"), episodeNumber)
            }?.absUrl("href") ?: return emptyList()

        val epDoc = fetchDoc(epUrl, bestAnimeUrl)
        val results = mutableListOf<RawMkvCandidate>()

        epDoc.select("div.download-eps").forEach { block ->
            val formatHeader = block.selectFirst("p b, p")?.text().orEmpty()
            block.select("ul li").forEach { li ->
                val strongText = li.selectFirst("strong")?.text().orEmpty()
                if (!TitleMatcher.isMkvBlock(formatHeader, strongText)) return@forEach
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach

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
                                pageReferer = epUrl
                            )
                        )
                    }
                }
            }
        }
        return results
    }
}

class OploverzSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.OPLOVERZ.defaultBaseUrl,
    private val backApiUrl: String = "https://backapi.oploverz.ac"
) : BaseHtmlMkvProvider(clients, baseUrl) {

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
        val searchUrl = "$baseUrl/?s=${encodeQuery(animeTitle)}"
        val searchDoc = fetchDoc(searchUrl, "$baseUrl/")
        val bestAnimeUrl = searchDoc.select("div.bsx a[href], article.bs a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
            }
            .maxByOrNull { it.first }
            ?.takeIf { it.first >= 0.25 }
            ?.second ?: return emptyList()

        val animeDoc = fetchDoc(bestAnimeUrl, searchUrl)
        val epUrl = animeDoc.select("div.eplister ul li a[href], ul#episodelist li a[href]")
            .firstOrNull { a ->
                TitleMatcher.matchesEpisode(a.text(), a.absUrl("href"), episodeNumber)
            }?.absUrl("href") ?: return emptyList()

        val epDoc = fetchDoc(epUrl, bestAnimeUrl)
        val results = mutableListOf<RawMkvCandidate>()

        epDoc.select("div.soraddlx, div.mctnx, div.download-box").forEach { block ->
            val blockTitle = block.selectFirst(".sorattlx, h3, h4")?.text().orEmpty()
            block.select(".soraurlx, li").forEach { row ->
                val qualityText = row.selectFirst("strong")?.text().orEmpty()
                if (!TitleMatcher.isMkvBlock(blockTitle, qualityText)) return@forEach
                val quality = MkvQuality.fromText(qualityText) ?: return@forEach
                if (quality !in targetQualities) return@forEach

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
                                pageReferer = epUrl
                            )
                        )
                    }
                }
            }
        }

        epDoc.select("div.flex.flex-row.items-start").forEach { row ->
            val qualityText = row.selectFirst("div.w-20 > p")?.text().orEmpty()
            if (!TitleMatcher.isMkvBlock("", qualityText)) return@forEach
            val quality = MkvQuality.fromText(qualityText) ?: return@forEach
            if (quality !in targetQualities) return@forEach
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
                            pageReferer = epUrl
                        )
                    )
                }
            }
        }

        epDoc.select("a.dsu-download[href]").forEach { a ->
            val href = a.absUrl("href").trim()
            val text = a.text().trim()
            if (href.startsWith("http") && TitleMatcher.isMkvBlock("", text)) {
                val quality = MkvQuality.fromText(text) ?: MkvQuality.P720
                if (quality in targetQualities && !HosterFilter.isBlacklisted(text, href)) {
                    results.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = quality,
                            hosterLabel = text.ifBlank { "Hoster" },
                            rawUrl = href,
                            pageReferer = epUrl
                        )
                    )
                }
            }
        }
        return results
    }

    private fun searchBackApiOploverz(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val searchReq = Request.Builder()
            .url("$backApiUrl/api/series?q=${encodeQuery(animeTitle)}")
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

        val seriesUrl = "$baseUrl/series/$seriesSlug"
        val animeDoc = fetchDoc(seriesUrl, "$baseUrl/")
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
                if (!TitleMatcher.isMkvBlock(blockTitle, qualityText)) return@forEach
                val quality = MkvQuality.fromText(qualityText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
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
                                pageReferer = epUrl
                            )
                        )
                    }
                }
            }
        }
        return results
    }
}

class KusonimeSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.KUSONIME.defaultBaseUrl
) : BaseHtmlMkvProvider(clients, baseUrl) {

    override val priority: SourcePriority = SourcePriority.KUSONIME

    override fun findEpisodeMkvCandidates(
        animeTitle: String,
        episodeNumber: Int,
        targetQualities: Set<MkvQuality>
    ): List<RawMkvCandidate> {
        val searchUrl = "$baseUrl/?s=${encodeQuery(animeTitle)}"
        val searchDoc = fetchDoc(searchUrl, "$baseUrl/")
        val bestPostUrl = searchDoc.select("div.kover a[href], h2.episodeye a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href")
                val title = a.attr("title").ifBlank { a.text() }.trim()
                if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
            }
            .maxByOrNull { it.first }
            ?.takeIf { it.first >= 0.25 }
            ?.second ?: return emptyList()

        val postDoc = fetchDoc(bestPostUrl, searchUrl)
        val results = mutableListOf<RawMkvCandidate>()

        postDoc.select("div.smokeddlrh, div.dlbodz").forEach { block ->
            val header = block.selectFirst(".smokettlrh, h3")?.text().orEmpty()
            block.select("div.smokeurlrh").forEach { row ->
                val strongText = row.selectFirst("strong")?.text().orEmpty()
                val quality = MkvQuality.fromText(strongText) ?: return@forEach
                if (quality !in targetQualities) return@forEach
                val isMkvOrBatch = TitleMatcher.isMkvBlock(header, strongText) ||
                    header.contains("batch", ignoreCase = true)
                if (!isMkvOrBatch) return@forEach

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
                                isBatchArchive = header.contains("batch", ignoreCase = true)
                            )
                        )
                    }
                }
            }
        }
        return results
    }
}

class MkvCascadeResolver(
    private val clients: IsolatedHttpClients,
    private val providers: List<MkvSourceProvider> = listOf(
        OtakudesuSourceProvider(clients),
        SamehadakuSourceProvider(clients),
        OploverzSourceProvider(clients),
        KusonimeSourceProvider(clients)
    )
) {
    private val unshortener = LinkUnshortener(clients)
    private val extractorRegistry = MkvHosterExtractorRegistry(clients)

    fun resolve(
        animeTitle: String,
        episodeNumber: Int,
        preferredQuality: MkvQuality = MkvQuality.P1080,
        allowBatchFallback: Boolean = false
    ): CascadeResult {
        val orderedProviders = providers.sortedBy { it.priority.rank }
        val attempted = mutableListOf<SourcePriority>()
        val errors = linkedMapOf<SourcePriority, String>()
        val cfHosts = linkedSetOf<String>()

        val qualityOrder = listOf(preferredQuality) +
            MkvQuality.ALL_DESC.filterNot { it == preferredQuality }

        for (provider in orderedProviders) {
            attempted.add(provider.priority)
            val rawCandidates = try {
                provider.findEpisodeMkvCandidates(animeTitle, episodeNumber, MkvQuality.ALL_DESC.toSet())
            } catch (cf: CloudflareChallengeException) {
                cfHosts.add(cf.host)
                errors[provider.priority] = cf.message ?: "Cloudflare challenge"
                continue
            } catch (e: Exception) {
                errors[provider.priority] = e.message ?: e::class.java.simpleName
                continue
            }

            val usableCandidates = rawCandidates
                .filter { allowBatchFallback || !it.isBatchArchive }
                .sortedWith(
                    compareBy<RawMkvCandidate> { qualityOrder.indexOf(it.quality) }
                        .thenBy { HosterFilter.rank(it.hosterLabel, it.rawUrl) }
                )

            val resolvedList = mutableListOf<ResolvedMkvMedia>()
            for (candidate in usableCandidates) {
                val extracted = runCatching {
                    val finalUrl = unshortener.unshorten(candidate.rawUrl, candidate.pageReferer)
                    extractorRegistry.extract(finalUrl, candidate.pageReferer)
                }.getOrNull() ?: continue

                resolvedList.add(
                    ResolvedMkvMedia(
                        source = candidate.source,
                        quality = candidate.quality,
                        hoster = extracted.hoster,
                        directUrl = extracted.directUrl,
                        referer = extracted.referer,
                        supportsRange = extracted.supportsRange,
                        sizeLabel = candidate.sizeLabel,
                        isBatchArchive = candidate.isBatchArchive || extracted.isArchive
                    )
                )
                if (resolvedList.size >= 4) break
            }

            if (resolvedList.isNotEmpty()) {
                return CascadeResult.Success(
                    selected = resolvedList.first(),
                    alternatives = resolvedList.drop(1),
                    diagnostics = CascadeDiagnostics(attempted, errors, cfHosts)
                )
            }
            errors[provider.priority] = "Tidak ada tautan MKV 480p/720p/1080p yang berhasil diekstrak"
        }

        return CascadeResult.Failure(
            reason = "Semua 4 sumber (Otakudesu -> Samehadaku -> Oploverz -> Kusonime) gagal menghasilkan MKV 480p/720p/1080p.",
            diagnostics = CascadeDiagnostics(attempted, errors, cfHosts)
        )
    }
}

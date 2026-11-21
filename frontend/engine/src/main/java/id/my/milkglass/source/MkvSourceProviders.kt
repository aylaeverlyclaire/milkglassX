package id.my.milkglass.source

import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.net.CloudflareChallengeException
import id.my.milkglass.net.IsolatedHttpClients
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

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
                if (StreamingFirstGuards.isWafBlockedHtml(html)) {
                    throw IllegalStateException("WAF blocked dari $finalUrl")
                }
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
                    container = MediaContainer.UNKNOWN,
                    isStreamEmbed = true
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
                    ?.takeIf { it.first >= 0.40 }
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
        epDoc: Document,
        epUrl: String,
        root: String,
        targetQualities: Set<MkvQuality>,
        existing: List<RawMkvCandidate>
    ): List<RawMkvCandidate> {
        val seenUrls = existing.mapTo(HashSet()) { it.rawUrl }
        val out = mutableListOf<RawMkvCandidate>()
        // 1) Panen iframe default yang sudah ada di HTML awal halaman episode (0 request AJAX)
        out.addAll(StreamingFirstGuards.harvestOtakudesuDefaultEmbeds(epDoc, epUrl, targetQualities, seenUrls))

        val mirrorLinks = epDoc.select("ul.m360p li a[data-content], ul.m480p li a[data-content], ul.m720p li a[data-content]")
        if (mirrorLinks.isEmpty()) return out
        val nonce = runCatching {
            val form = okhttp3.FormBody.Builder().add("action", "aa1208d27f29ca340c92c66d1926f13f").build()
            val req = Request.Builder()
                .url("$root/wp-admin/admin-ajax.php")
                .post(form)
                .header("Referer", epUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            clients.sourceClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JSONObject(resp.body?.string().orEmpty()).optString("data").takeIf { it.isNotBlank() }
            }
        }.getOrNull() ?: return out

        for (a in mirrorLinks) {
            if (out.size >= 4) break
            val label = a.text().trim()
            if (HosterFilter.isBlacklisted(label, label)) continue
            val ulClass = a.parents().firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
            val quality = MkvQuality.fromText(ulClass) ?: MkvQuality.P720
            if (quality !in targetQualities) continue
            val payload = runCatching {
                val decoded = String(android.util.Base64.decode(a.attr("data-content"), android.util.Base64.DEFAULT))
                JSONObject(decoded)
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
                val req = Request.Builder()
                    .url("$root/wp-admin/admin-ajax.php")
                    .post(form)
                    .header("Referer", epUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                clients.sourceClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val dataB64 = JSONObject(resp.body?.string().orEmpty()).optString("data")
                    if (dataB64.isBlank()) return@use null
                    val iframeHtml = String(android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT))
                    Jsoup.parse(iframeHtml, root).selectFirst("iframe[src]")?.absUrl("src")?.trim()
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
                        container = MediaContainer.MP4,
                        isStreamEmbed = true
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
                    ?.takeIf { it.first >= 0.40 }
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
        epDoc: Document,
        epUrl: String,
        root: String,
        targetQualities: Set<MkvQuality>,
        existing: List<RawMkvCandidate>
    ): List<RawMkvCandidate> {
        val seenUrls = existing.mapTo(HashSet()) { it.rawUrl }
        val out = mutableListOf<RawMkvCandidate>()
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
                            container = MediaContainer.MP4,
                            isStreamEmbed = true
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
                val req = Request.Builder()
                    .url("$root/wp-admin/admin-ajax.php")
                    .post(form)
                    .header("Referer", epUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build()
                clients.sourceClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string().orEmpty()
                    Jsoup.parse(html, root).selectFirst("iframe[src]")?.absUrl("src")?.trim()
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
                        container = MediaContainer.MP4,
                        isStreamEmbed = true
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
                    ?.takeIf { it.first >= 0.40 }
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
                    ?.takeIf { it.first >= 0.40 }
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
            if (bestScore >= 0.40) bestSlug else null
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
                    ?.takeIf { it.first >= 0.40 }
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
                        container = MediaContainer.MP4,
                        isStreamEmbed = true
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
                        container = MediaContainer.MP4,
                        isStreamEmbed = true
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
                    container = MediaContainer.MP4,
                    isStreamEmbed = true
                )
            )
        }

        results.addAll(harvestFallbackCandidates(epDoc, resolvedEpUrl, results))
        return results
    }
}

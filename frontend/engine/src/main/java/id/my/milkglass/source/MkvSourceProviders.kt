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
                val lowFinal = finalUrl.lowercase(Locale.ROOT)
                val isIspBlocked = lowFinal.contains("internetpositif") ||
                    lowFinal.contains("trustpositif") ||
                    lowFinal.contains("uzone.id") ||
                    lowFinal.contains("kominfo.go.id/block")
                if (isIspBlocked || StreamingFirstGuards.isWafBlockedHtml(html)) {
                    if (remoteHtmlFetcher != null) {
                        val remoteHtml = runCatching { remoteHtmlFetcher?.invoke(url) }.getOrNull()
                        if (!remoteHtml.isNullOrBlank()) {
                            return Jsoup.parse(remoteHtml, url)
                        }
                    }
                    throw IllegalStateException(if (isIspBlocked) "ISP blocked dari $finalUrl" else "WAF blocked dari $finalUrl")
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
            val singleMovieEpUrl = if (episodeNumber == 1 && parsedMap.isEmpty()) {
                val nonBatchEps = orderedEpLinks
                    .map { it.absUrl("href") }
                    .filter { it.contains("/episode/") && !it.contains("/batch/", ignoreCase = true) }
                    .distinct()
                if (nonBatchEps.size == 1) nonBatchEps.first() else null
            } else null
            if (singleMovieEpUrl != null) {
                parsedMap.putIfAbsent(1, singleMovieEpUrl)
                SeriesEpisodeMapCache.putAll(priority, bestAnimeUrl, parsedMap)
            }
            epUrl = parsedMap[episodeNumber] ?: orderedEpLinks
                .firstOrNull { a ->
                    val href = a.absUrl("href")
                    href.contains("/episode/") && TitleMatcher.matchesEpisode(a.text(), href, episodeNumber)
                }?.absUrl("href") ?: singleMovieEpUrl ?: return emptyList()
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
        val hasHealthyDefault = out.any { !it.rawUrl.contains("nekocloud", ignoreCase = true) }
        if (hasHealthyDefault) return out

        val mirrorLinks = epDoc.select("ul.m360p li a[data-content], ul.m480p li a[data-content], ul.m720p li a[data-content]")
        if (mirrorLinks.isEmpty()) return out
        val nonce = runCatching {
            val nonceAction = RemoteRulesStore.globalSelectorFor(SourcePriority.OTAKUDESU, "ajax_nonce_action", "aa1208d27f29ca340c92c66d1926f13f")
            val form = okhttp3.FormBody.Builder().add("action", nonceAction).build()
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

        // Prioritaskan mirror sehat & cepat (odstream -> odcdn -> filedon -> otakuwatch),
        // dan fail-fast skip mirror rusak/jebakan iklan (vidhide, ondesuhd, moedesu, nekocloud, mega).
        val deadOrAdMirrorTokens = listOf("vidhide", "ondesuhd", "moedesu", "nekocloud", "mega", "yourupload")
        val rankedMirrors = mirrorLinks.sortedBy { a ->
            val low = a.text().trim().lowercase(Locale.ROOT)
            when {
                low.contains("odstream") -> 0
                low.contains("odcdn") -> 1
                low.contains("filedon") -> 2
                low.contains("otakuwatch") -> 3
                else -> 10
            }
        }
        for (a in rankedMirrors) {
            if (out.size >= 3) break
            val label = a.text().trim()
            val lowLabel = label.lowercase(Locale.ROOT)
            if (HosterFilter.isBlacklisted(label, label) || deadOrAdMirrorTokens.any { lowLabel.contains(it) }) continue
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
                    .add("action", RemoteRulesStore.globalSelectorFor(SourcePriority.OTAKUDESU, "ajax_mirror_action", "2a3505c93b0035d3f455df82bf976b84"))
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
                searchUrl = "$root/?s=${encodeQuery(q)}"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                val shSearchSel = RemoteRulesStore.globalSelectorFor(
                    SourcePriority.SAMEHADAKU,
                    "search_item",
                    ".listupd .bs .bsx > a[href], article.bs .bsx a[href], div.animposx > a[href], article.animpost div.animposx > a[href], article.animepost div.animposx a[href], a[href*='/anime/']"
                )
                bestAnimeUrl = searchDoc.select(shSearchSel)
                    .mapNotNull { a ->
                        val href = a.absUrl("href")
                        if (!href.contains("/anime/")) return@mapNotNull null
                        val title = a.selectFirst(".tt, h2, .data .title h2")?.text()?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: a.attr("title").ifBlank { a.text() }.trim()
                        if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
                    }
                    .maxByOrNull { it.first }
                    ?.takeIf { it.first >= 0.40 }
                    ?.second
                if (bestAnimeUrl != null) break
            }
            // Fallback slug langsung untuk Season 1 lama yang tenggelam di halaman 2–4 hasil pencarian WordPress
            if (bestAnimeUrl == null) {
                val parsed = TitleMatcher.parse(animeTitle)
                if ((parsed.season == null || parsed.season == 1) && parsed.canonicalBase.length >= 3) {
                    val slugGuess = parsed.canonicalBase.replace(" ", "-")
                    val directUrl = "$root/anime/$slugGuess/"
                    val probeDoc = runCatching { fetchDoc(directUrl, "$root/") }.getOrNull()
                    val pageTitle = probeDoc?.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty()
                    if (pageTitle.isNotBlank() && TitleMatcher.score(animeTitle, pageTitle) >= 0.55) {
                        bestAnimeUrl = directUrl
                    }
                }
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

            val shEpSel = RemoteRulesStore.globalSelectorFor(SourcePriority.SAMEHADAKU, "episode_row", "div.eplister ul li, .eplister ul li, div.lstepsiode.listeps ul li, .listeps ul li, .lstepsiode ul li, .episode-lainnya ul li")
            val epRows = animeDoc.select(shEpSel)
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
                ?: run {
                    if (episodeNumber == 1 && parsedMap.isEmpty() && epRows.size == 1) {
                        val singleHref = epRows.first()?.selectFirst("h4 a[href], .lchx a[href], a[href]")?.absUrl("href")
                        if (!singleHref.isNullOrBlank() && singleHref.startsWith("http") && !singleHref.contains("/batch/", ignoreCase = true)) {
                            return@run singleHref
                        }
                    }
                    null
                }
                ?: run {
                    // Fallback lintas cour: jika ep > 12 tidak ada di halaman Part 1 (mis. Tensura S2 gabungan 24 ep
                    // dari Otakudesu jatuh ke Samehadaku yang memisah Part 2), coba cari "$animeTitle Part 2" ep - 12.
                    if (episodeNumber > 12 && !animeTitle.contains("part", ignoreCase = true) && !animeTitle.contains("cour", ignoreCase = true)) {
                        return findEpisodeMkvCandidates("$animeTitle Part 2", episodeNumber - 12, targetQualities)
                    }
                    return emptyList()
                }
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

        // 1. Themesia/samehadaku.li Select Video Server (<select class="mirror"> dengan value base64 atau URL)
        for (opt in epDoc.select("select.mirror option[value], select[name='mirror'] option[value], .mobius select option[value]")) {
            val rawVal = opt.attr("value").trim()
            if (rawVal.isBlank()) continue
            val label = opt.text().trim()
            if (HosterFilter.isBlacklisted(label, rawVal)) continue
            val decodedSrc = when {
                rawVal.startsWith("http://") || rawVal.startsWith("https://") -> rawVal
                rawVal.length >= 16 -> runCatching {
                    val html = String(Base64.getDecoder().decode(rawVal), Charsets.UTF_8)
                    Jsoup.parse(html, root).selectFirst("iframe[src], source[src]")?.absUrl("src")?.trim()
                }.getOrNull()
                else -> null
            }
            if (!decodedSrc.isNullOrBlank() && decodedSrc.startsWith("http") &&
                !HosterFilter.isBlacklisted(label, decodedSrc) && seenUrls.add(decodedSrc)
            ) {
                val q = MkvQuality.fromText("$label $decodedSrc") ?: MkvQuality.P720
                if (q in targetQualities) {
                    out.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = q,
                            hosterLabel = label.ifBlank { "SamehadakuMirror" },
                            rawUrl = decodedSrc,
                            pageReferer = epUrl,
                            container = MediaContainer.MP4,
                            isStreamEmbed = true
                        )
                    )
                }
            }
        }

        // 2. Iframe embed langsung di halaman episode
        for (ifr in epDoc.select("#embed_holder iframe[src], .video-content iframe[src], #player_embed iframe[src], .player-embed iframe[src], iframe[src]")) {
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

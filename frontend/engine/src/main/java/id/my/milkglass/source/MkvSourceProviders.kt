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
            val bonusCandidates = mutableListOf<Pair<Int, String>>()
            orderedEpLinks.forEach { a ->
                val href = a.absUrl("href")
                if (href.contains("/episode/")) {
                    val txt = a.text()
                    SeriesEpisodeMapCache.extractEpisodeNumber(txt, href)?.let { num ->
                        if (SeriesEpisodeMapCache.isBonusOrSpecialLink(txt, href)) {
                            bonusCandidates.add(num to href)
                        } else {
                            parsedMap.putIfAbsent(num, href)
                        }
                    }
                }
            }
            bonusCandidates.forEach { (num, href) -> parsedMap.putIfAbsent(num, href) }
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
        val mirrorLinks = epDoc.select("ul.m360p li a[data-content], ul.m480p li a[data-content], ul.m720p li a[data-content]")
        val deadOrAdMirrorTokens = listOf("vidhide", "ondesuhd", "moedesu", "nekocloud", "mega", "yourupload")
        if (!StreamingFirstGuards.shouldFetchOtakudesuMirrors(out, mirrorLinks, targetQualities, deadOrAdMirrorTokens)) {
            return out
        }
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

        // Prioritaskan resolusi tertinggi yang diminta (1080p/720p dulu), lalu mirror sehat & cepat
        // (odstream -> odcdn -> filedon -> otakuwatch), dan fail-fast skip mirror rusak/iklan.
        val rankedMirrors = mirrorLinks.sortedWith(
            compareBy<org.jsoup.nodes.Element> { a ->
                val ulClass = a.parents().firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
                when (MkvQuality.fromText(ulClass)) {
                    MkvQuality.P1080 -> 0
                    MkvQuality.P720 -> 1
                    MkvQuality.P480 -> 2
                    MkvQuality.P360 -> 3
                    null -> 4
                }
            }.thenBy { a ->
                val low = a.text().trim().lowercase(Locale.ROOT)
                when {
                    low.contains("odstream") -> 0
                    low.contains("odcdn") -> 1
                    low.contains("filedon") -> 2
                    low.contains("otakuwatch") -> 3
                    low.contains("mp4load") || low.contains("mp4upload") -> 4
                    else -> 10
                }
            }
        )
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

class YlnimeSourceProvider(
    clients: IsolatedHttpClients,
    baseUrl: String = SourcePriority.YLNIME.defaultBaseUrl,
    slugCache: SourceSlugCache? = null,
    baseUrlProvider: (() -> String)? = null,
    indexLookup: ((SourcePriority, String) -> String?)? = null,
    remoteHtmlFetcher: ((String) -> String?)? = null
) : BaseHtmlMkvProvider(clients, baseUrl, slugCache, baseUrlProvider, indexLookup, remoteHtmlFetcher) {

    override val priority: SourcePriority = SourcePriority.YLNIME

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
                searchUrl = "$root/index.php?search=${encodeQuery(q)}"
                val searchDoc = fetchDoc(searchUrl, "$root/")
                val ylSearchSel = RemoteRulesStore.globalSelectorFor(
                    SourcePriority.YLNIME,
                    "search_item",
                    "a[href*='?series=']"
                )
                val genericLabels = setOf("cover", "poster", "thumbnail", "thumb", "image", "img", "logo", "banner", "watch", "tonton", "nonton", "detail")
                bestAnimeUrl = searchDoc.select(ylSearchSel)
                    .mapNotNull { a ->
                        val href = a.absUrl("href").trim()
                        if (!href.contains("series=") || href.contains("episode=", ignoreCase = true)) return@mapNotNull null
                        val card = a.closest(".card")
                        val title = listOf(
                            a.selectFirst(".tt, h2, h3, .title, .card-title, h6, h5")?.text().orEmpty(),
                            a.attr("title"),
                            a.text(),
                            card?.selectFirst(".card-title, h6, h5")?.text().orEmpty(),
                            card?.selectFirst("img[alt]")?.attr("alt").orEmpty()
                        ).map { it.trim() }.firstOrNull { it.length >= 2 && it.lowercase(Locale.ROOT) !in genericLabels }.orEmpty()
                        if (href.isBlank() || title.isBlank()) null else TitleMatcher.score(animeTitle, title) to href
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
            var animeDoc = runCatching { fetchDoc(bestAnimeUrl, searchUrl) }.getOrElse {
                if (!Thread.currentThread().isInterrupted) slugCache?.invalidate(priority, animeTitle)
                throw it
            }
            var epLinks = animeDoc.select("a[href*='episode=']")
            if (epLinks.size <= 1) {
                val altUrl = if (Regex("(?:%2[fF]|/)+$").containsMatchIn(bestAnimeUrl)) {
                    bestAnimeUrl.replace(Regex("(?:%2[fF]|/)+$"), "")
                } else {
                    "$bestAnimeUrl%2F"
                }
                if (altUrl != bestAnimeUrl) {
                    val altDoc = runCatching { fetchDoc(altUrl, searchUrl) }.getOrNull()
                    val altLinks = altDoc?.select("a[href*='episode=']")
                    if (altDoc != null && altLinks != null && altLinks.size > epLinks.size) {
                        animeDoc = altDoc
                        epLinks = altLinks
                        bestAnimeUrl = altUrl
                        slugCache?.put(priority, animeTitle, bestAnimeUrl)
                    }
                }
            }
            val parsedMap = linkedMapOf<Int, String>()
            val distinctUrls = linkedSetOf<String>()
            epLinks.forEach { a ->
                val href = a.absUrl("href").trim()
                if (href.startsWith("http")) {
                    distinctUrls.add(href)
                    SeriesEpisodeMapCache.extractEpisodeNumber(a.text(), href)?.let { num ->
                        parsedMap.putIfAbsent(num, href)
                    }
                }
            }
            val epIdRe = Regex("[?&]episode=([^&#]+)", RegexOption.IGNORE_CASE)
            val distinctEpIds = distinctUrls.mapNotNull { epIdRe.find(it)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT) }.toSet()
            if (episodeNumber == 1 && parsedMap.isEmpty() && (distinctUrls.size == 1 || distinctEpIds.size == 1)) {
                parsedMap[1] = distinctUrls.first()
            }
            SeriesEpisodeMapCache.putAll(priority, bestAnimeUrl, parsedMap)
            epUrl = parsedMap[episodeNumber] ?: epLinks.firstOrNull { a ->
                val href = a.absUrl("href").trim()
                href.startsWith("http") && TitleMatcher.matchesEpisode(a.text(), href, episodeNumber)
            }?.absUrl("href") ?: return emptyList()
        }

        val targetReso = when {
            MkvQuality.P1080 in targetQualities -> "1080p"
            MkvQuality.P720 in targetQualities -> "720p"
            MkvQuality.P480 in targetQualities -> "480p"
            else -> "360p"
        }
        val epUrlWithReso = if (epUrl.contains("reso=")) epUrl else {
            val sep = if (epUrl.contains("?")) "&" else "?"
            "$epUrl${sep}reso=$targetReso"
        }
        val epDoc = runCatching { fetchDoc(epUrlWithReso, bestAnimeUrl) }.getOrElse {
            if (!Thread.currentThread().isInterrupted) SeriesEpisodeMapCache.invalidate(priority, bestAnimeUrl)
            throw it
        }

        val results = mutableListOf<RawMkvCandidate>()
        val seenUrls = hashSetOf<String>()
        val qDefault = MkvQuality.fromText(targetReso) ?: MkvQuality.P720

        // 1. Pemutar HTML5 <video><source src="..."> (server Animeverse YLnime)
        val fallbackVideoCandidates = mutableListOf<RawMkvCandidate>()
        epDoc.select("video source[src], video[src]").forEach { el ->
            val src = el.absUrl("src").trim()
            if (src.startsWith("http") && !HosterFilter.isBlacklisted("Animeverse", src) && seenUrls.add(src)) {
                val q = MkvQuality.fromText(src) ?: qDefault
                val cand = RawMkvCandidate(
                    source = priority,
                    quality = q,
                    hosterLabel = "Animeverse",
                    rawUrl = src,
                    pageReferer = epUrlWithReso,
                    container = if (src.contains(".m3u8", ignoreCase = true)) MediaContainer.HLS else MediaContainer.MP4,
                    isStreamEmbed = true
                )
                if (q in targetQualities) {
                    results.add(cand)
                } else {
                    fallbackVideoCandidates.add(cand)
                }
            }
        }
        if (results.isEmpty() && fallbackVideoCandidates.isNotEmpty()) {
            results.addAll(fallbackVideoCandidates)
        }

        // 1b. Ekstrak URL video/Pixeldrain dari atribut data-src/data-video/data-url dan inline <script> YLnime
        epDoc.select("[data-src], [data-video], [data-url]").forEach { el ->
            val raw = el.attr("data-src").ifBlank { el.attr("data-video") }.ifBlank { el.attr("data-url") }.trim()
            if (raw.startsWith("http") && !HosterFilter.isBlacklisted("Animeverse", raw) && seenUrls.add(raw)) {
                val low = raw.lowercase(Locale.ROOT)
                if (low.contains(".mp4") || low.contains(".m3u8") || low.contains("pixeldrain.")) {
                    val q = MkvQuality.fromText("${el.text()} $raw") ?: qDefault
                    results.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = q,
                            hosterLabel = if (low.contains("pixeldrain.")) "Pixeldrain" else "Animeverse",
                            rawUrl = raw,
                            pageReferer = epUrlWithReso,
                            container = if (low.contains(".m3u8")) MediaContainer.HLS else MediaContainer.MP4,
                            isStreamEmbed = !low.contains("pixeldrain.")
                        )
                    )
                }
            }
        }
        val streamsJsonRe = Regex("""(?:const|let|var)\s+streams\s*=\s*(\[[\s\S]*?\])\s*;""")
        val scriptMediaRe = Regex(
            """["'](https?://(?:[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?|(?:www\.)?pixeldrain\.(?:com|lu)/(?:u|api/file)/[A-Za-z0-9_-]+(?:\?[^"'\s<>]*)?))["']""",
            RegexOption.IGNORE_CASE
        )
        epDoc.select("script").forEach { sc ->
            val rawBody = sc.data()
            if (rawBody.isBlank()) return@forEach
            streamsJsonRe.find(rawBody)?.groupValues?.getOrNull(1)?.let { arrJson ->
                runCatching {
                    val arr = org.json.JSONArray(arrJson)
                    for (idx in 0 until arr.length()) {
                        val obj = arr.optJSONObject(idx) ?: continue
                        val link = obj.optString("link").replace("\\/", "/").trim().replace(" ", "%20")
                        if (!link.startsWith("http")) continue
                        val low = link.lowercase(Locale.ROOT)
                        val hostLabel = when {
                            low.contains("pixeldrain.") -> "Pixeldrain"
                            low.contains("animekita.") -> "AnimeKita"
                            else -> "Animeverse"
                        }
                        if (HosterFilter.isBlacklisted(hostLabel, link) || !seenUrls.add(link)) continue
                        val q = MkvQuality.fromText(obj.optString("reso")) ?: MkvQuality.fromText(link) ?: qDefault
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = q,
                                hosterLabel = hostLabel,
                                rawUrl = link,
                                pageReferer = epUrlWithReso,
                                container = if (low.contains(".m3u8")) MediaContainer.HLS else MediaContainer.MP4,
                                isStreamEmbed = false
                            )
                        )
                    }
                }
            }
            val body = rawBody.replace("\\/", "/")
            scriptMediaRe.findAll(body).forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.startsWith("http") && !HosterFilter.isBlacklisted("Animeverse", raw) && seenUrls.add(raw)) {
                    val low = raw.lowercase(Locale.ROOT)
                    val q = MkvQuality.fromText(raw) ?: qDefault
                    results.add(
                        RawMkvCandidate(
                            source = priority,
                            quality = q,
                            hosterLabel = if (low.contains("pixeldrain.")) "Pixeldrain" else "Animeverse",
                            rawUrl = raw,
                            pageReferer = epUrlWithReso,
                            container = if (low.contains(".m3u8")) MediaContainer.HLS else MediaContainer.MP4,
                            isStreamEmbed = false
                        )
                    )
                }
            }
        }

        // 2. Tautan Pixeldrain & hoster cepat langsung di halaman YLnime
        results.addAll(harvestFallbackCandidates(epDoc, epUrlWithReso, results))

        // 3. Fallback WebView EmbedMediaSniffer untuk player HTML5 YLnime yang memuat stream via JavaScript
        //    (mis. Movie tanpa link download terpisah atau saat 1080p auto-fallback ke 720p di sisi klien).
        if (results.isEmpty() && epUrlWithReso.startsWith("http")) {
            val no1080 = epDoc.text().contains("1080p tidak tersedia", ignoreCase = true)
            val sniffUrl = if (no1080 && epUrlWithReso.contains("reso=1080p", ignoreCase = true)) {
                epUrlWithReso.replace("reso=1080p", "reso=720p", ignoreCase = true)
            } else {
                epUrlWithReso
            }
            val sniffQuality = if (no1080 && qDefault == MkvQuality.P1080) MkvQuality.P720 else qDefault
            results.add(
                RawMkvCandidate(
                    source = priority,
                    quality = sniffQuality,
                    hosterLabel = "YLnimePlayer",
                    rawUrl = sniffUrl,
                    pageReferer = bestAnimeUrl,
                    container = MediaContainer.MP4,
                    isStreamEmbed = true
                )
            )
        }
        return results
    }
}

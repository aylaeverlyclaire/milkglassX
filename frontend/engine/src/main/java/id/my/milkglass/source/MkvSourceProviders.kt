package id.my.milkglass.source

import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.net.CloudflareChallengeException
import id.my.milkglass.net.IsolatedHttpClients
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Single-flight + cache singkat (60 dtk) untuk halaman series agar probeLiveEpisodes,
     * prefetchEpisode, MkvCascadeResolver, dan ResolutionConsolidator tidak mengunduh
     * halaman series yang sama berulang kali secara paralel.
     */
    protected fun fetchSeriesDocShared(url: String, referer: String? = null): Document {
        val normKey = url.trim().trimEnd('/')
        val now = System.currentTimeMillis()
        seriesDocCache[normKey]?.let { (ts, html) ->
            if (now - ts <= SERIES_DOC_TTL_MS && html.isNotBlank()) {
                return Jsoup.parse(html, url)
            }
        }
        val mine = CompletableFuture<String>()
        val existing = inFlightSeriesDocs.putIfAbsent(normKey, mine)
        if (existing != null) {
            val joinedHtml = runCatching { existing.get(6_000L, TimeUnit.MILLISECONDS) }.getOrNull()
            if (!joinedHtml.isNullOrBlank()) {
                return Jsoup.parse(joinedHtml, url)
            }
        }
        return try {
            val doc = fetchDoc(url, referer)
            val html = doc.outerHtml()
            if (html.length >= 128) {
                if (seriesDocCache.size > 64) seriesDocCache.clear()
                seriesDocCache[normKey] = System.currentTimeMillis() to html
                mine.complete(html)
            } else {
                mine.complete("")
            }
            doc
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlightSeriesDocs.remove(normKey, mine)
        }
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

    protected fun inferElementQuality(el: Element, label: String, href: String, defaultQuality: MkvQuality = MkvQuality.P720): MkvQuality {
        MkvQuality.fromText(
            listOf(
                el.attr("data-reso"),
                el.attr("data-quality"),
                el.attr("data-res"),
                el.attr("size"),
                el.attr("label"),
                el.attr("title"),
                label,
                href
            ).joinToString(" ")
        )?.let { return it }

        val parent = el.parent()
        if (parent != null) {
            val directBadges = parent.select("strong, b, .badge, .reso, .quality").joinToString(" ") { it.text() }
            MkvQuality.fromText("$directBadges ${parent.ownText()} ${parent.previousElementSibling()?.text().orEmpty()}")?.let { return it }
            MkvQuality.fromText(parent.text())?.let { return it }
            val grand = parent.parent()
            if (grand != null) {
                val grandHeader = grand.selectFirst("strong, b, .badge, .card-header, h4, h5, h6, th, td")?.text().orEmpty()
                val grandPrev = grand.previousElementSibling()?.text().orEmpty()
                MkvQuality.fromText("$grandHeader $grandPrev ${grand.ownText()}")?.let { return it }
            }
        }
        return defaultQuality
    }

    protected fun harvestFallbackCandidates(
        epDoc: Document,
        epUrl: String,
        existing: List<RawMkvCandidate>,
        defaultQuality: MkvQuality = MkvQuality.P720
    ): List<RawMkvCandidate> {
        val seenKeys = existing.mapTo(HashSet()) { "${it.quality.label}|${it.rawUrl}" }
        val seenRawUrls = existing.mapTo(HashSet()) { it.rawUrl }
        val extra = mutableListOf<RawMkvCandidate>()

        epDoc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").trim()
            val label = a.text().trim()
            if (!href.startsWith("http")) return@forEach
            if (HosterFilter.isBlacklisted(label, href)) return@forEach
            if (!HosterFilter.isKnownPlayableHost(href)) return@forEach
            val quality = inferElementQuality(a, label, href, defaultQuality)
            val key = "${quality.label}|$href"
            if (key in seenKeys || href in seenRawUrls) return@forEach
            val rowCtx = "${a.parent()?.text().orEmpty()} ${a.parent()?.parent()?.ownText().orEmpty()}"
            val container = MediaContainer.detect(rowCtx, label, href)
            seenKeys.add(key)
            seenRawUrls.add(href)
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
            if (!src.startsWith("http") || src in seenRawUrls) return@forEach
            val low = src.lowercase(Locale.ROOT)
            if (low.contains("disqus") || low.contains("facebook") || low.contains("ads") ||
                low.contains("youtube.com") || low.contains("twitter.com") || low.contains("recaptcha")
            ) return@forEach
            if (HosterFilter.isBlacklisted("", src)) return@forEach
            val q = MkvQuality.fromText(src) ?: defaultQuality
            seenRawUrls.add(src)
            seenKeys.add("${q.label}|$src")
            extra.add(
                RawMkvCandidate(
                    source = priority,
                    quality = q,
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

    companion object {
        private const val SERIES_DOC_TTL_MS = 60_000L
        private val seriesDocCache = ConcurrentHashMap<String, Pair<Long, String>>()
        private val inFlightSeriesDocs = ConcurrentHashMap<String, CompletableFuture<String>>()

        fun seedSeriesHtmlCache(url: String, html: String) {
            if (url.isBlank() || html.length < 128) return
            if (seriesDocCache.size > 64) seriesDocCache.clear()
            seriesDocCache[url.trim().trimEnd('/')] = System.currentTimeMillis() to html
        }

        internal fun clearSeriesDocCacheForTest() {
            seriesDocCache.clear()
            inFlightSeriesDocs.clear()
        }
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
            val animeDoc = runCatching { fetchSeriesDocShared(bestAnimeUrl, searchUrl) }.getOrElse {
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

        val downloadUls = epDoc.select("div.download ul, .ondownload ul, .dlbox ul")
        downloadUls.forEachIndexed { ulIdx, ul ->
            val blockHeader = ul.previousElementSibling()?.text().orEmpty()
            val isImplicitMkvUl = downloadUls.size >= 2 && ulIdx == downloadUls.lastIndex
            ul.select("li").forEach { li ->
                val strongText = li.selectFirst("strong, b")?.text().orEmpty()
                val quality = MkvQuality.fromText(strongText) ?: MkvQuality.fromText(li.ownText()) ?: return@forEach
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
        // Dukung juga blok .soraurlx bila dipakai oleh template episode tertentu
        epDoc.select(".soraurlx").forEach { row ->
            val strongText = row.selectFirst("strong, b")?.text().orEmpty()
            val quality = MkvQuality.fromText(strongText) ?: return@forEach
            if (quality !in targetQualities) return@forEach
            val container = MediaContainer.detect("", strongText)
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
        val mirrorLinks = epDoc.select(
            "ul.m360p li a[data-content], ul.m480p li a[data-content], ul.m720p li a[data-content], ul.m1080p li a[data-content], .mirrorstream ul li a[data-content]"
        )
        val deadOrAdMirrorTokens = listOf("vidhide", "ondesuhd", "moedesu", "nekocloud", "mega", "yourupload")
        if (!StreamingFirstGuards.shouldFetchOtakudesuMirrors(out, mirrorLinks, targetQualities, deadOrAdMirrorTokens)) {
            return out
        }
        val nowMs = System.currentTimeMillis()
        val cachedNonce = nonceCache[root]?.takeIf { it.first > nowMs }?.second
        val nonce = cachedNonce ?: runCatching {
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
        }.getOrNull()?.also { fresh ->
            if (nonceCache.size > 16) nonceCache.clear()
            nonceCache[root] = (System.currentTimeMillis() + NONCE_TTL_MS) to fresh
        } ?: return out

        val targetOrder = targetQualities.toList()
        val rankedMirrors = mirrorLinks.sortedWith(
            compareBy<org.jsoup.nodes.Element> { a ->
                val ulClass = a.parents().firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
                val q = MkvQuality.fromText(ulClass) ?: MkvQuality.fromText(a.text())
                val idx = if (q != null) targetOrder.indexOf(q) else -1
                if (idx >= 0) idx else 10
            }.thenBy { a ->
                val low = a.text().trim().lowercase(Locale.ROOT)
                when {
                    low.contains("odstream") -> 0
                    low.contains("odcdn") -> 1
                    low.contains("filedon") -> 2
                    low.contains("blogs") || low.contains("blogger") -> 3
                    low.contains("ondesu2") || (low.contains("ondesu") && !low.contains("ondesuhd")) -> 4
                    low.contains("otakuwatch") -> 5
                    low.contains("mp4load") || low.contains("mp4upload") -> 6
                    else -> 10
                }
            }
        )

        data class MirrorTask(val label: String, val quality: MkvQuality, val id: String, val i: String, val q: String)
        val allValidTasks = mutableListOf<MirrorTask>()
        for (a in rankedMirrors) {
            val label = a.text().trim()
            val lowLabel = label.lowercase(Locale.ROOT)
            if (HosterFilter.isBlacklisted(label, label) || deadOrAdMirrorTokens.any { lowLabel.contains(it) }) continue
            val ulClass = a.parents().firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
            val payload = runCatching {
                val decoded = String(Base64.getDecoder().decode(a.attr("data-content").trim()))
                JSONObject(decoded)
            }.getOrNull() ?: continue
            val id = payload.optString("id")
            val i = payload.optString("i")
            val q = payload.optString("q")
            if (id.isBlank() || q.isBlank()) continue
            val quality = MkvQuality.fromText(ulClass) ?: MkvQuality.fromText(q) ?: MkvQuality.P720
            if (quality !in targetQualities) continue
            allValidTasks.add(MirrorTask(label, quality, id, i, q))
        }
        if (allValidTasks.isEmpty()) return out

        // Ambil 1 mirror terbaik per resolusi berbeda terlebih dahulu (agar 1080p, 720p, 480p semuanya terwakili),
        // lalu isi sisa slot hingga 4 tugas paralel.
        val tasks = mutableListOf<MirrorTask>()
        val coveredTaskQualities = HashSet<MkvQuality>()
        for (t in allValidTasks) {
            if (tasks.size >= 4) break
            if (coveredTaskQualities.add(t.quality)) {
                tasks.add(t)
            }
        }
        for (t in allValidTasks) {
            if (tasks.size >= 4) break
            if (t !in tasks) tasks.add(t)
        }

        val mirrorAction = RemoteRulesStore.globalSelectorFor(SourcePriority.OTAKUDESU, "ajax_mirror_action", "2a3505c93b0035d3f455df82bf976b84")
        val cs = ExecutorCompletionService<RawMkvCandidate?>(mirrorAjaxPool)
        val futures = ArrayList<Future<RawMkvCandidate?>>(tasks.size)
        try {
            for (task in tasks) {
                futures.add(
                    cs.submit {
                        val embedSrc = runCatching {
                            val form = okhttp3.FormBody.Builder()
                                .add("id", task.id)
                                .add("i", task.i)
                                .add("q", task.q)
                                .add("nonce", nonce)
                                .add("action", mirrorAction)
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
                                val iframeHtml = String(Base64.getDecoder().decode(dataB64.trim()))
                                Jsoup.parse(iframeHtml, root).selectFirst("iframe[src]")?.absUrl("src")?.trim()
                            }
                        }.getOrNull()
                        if (!embedSrc.isNullOrBlank() && embedSrc.startsWith("http") &&
                            !HosterFilter.isBlacklisted(task.label, embedSrc)
                        ) {
                            RawMkvCandidate(
                                source = priority,
                                quality = task.quality,
                                hosterLabel = task.label.ifBlank { "StreamMirror" },
                                rawUrl = embedSrc,
                                pageReferer = epUrl,
                                container = MediaContainer.MP4,
                                isStreamEmbed = true
                            )
                        } else null
                    }
                )
            }
            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_200L)
            repeat(futures.size) {
                if (out.size >= 5 || Thread.currentThread().isInterrupted) return@repeat
                val remNs = deadlineNs - System.nanoTime()
                if (remNs <= 0L) return@repeat
                val fut = runCatching { cs.poll(remNs, TimeUnit.NANOSECONDS) }.getOrNull() ?: return@repeat
                val cand = runCatching { fut.get() }.getOrNull()
                if (cand != null && seenUrls.add(cand.rawUrl)) {
                    out.add(cand)
                }
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
        return out
    }

    companion object {
        private const val NONCE_TTL_MS = 15L * 60L * 1000L
        private val nonceCache = ConcurrentHashMap<String, Pair<Long, String>>()
        private val mirrorSeq = AtomicInteger(1)
        internal val mirrorAjaxPool = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "milkglass-mirror-${mirrorSeq.getAndIncrement()}").apply { isDaemon = true }
        }

        internal fun clearNonceCacheForTest() {
            nonceCache.clear()
        }
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
            var animeDoc = runCatching { fetchSeriesDocShared(bestAnimeUrl, searchUrl) }.getOrElse {
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
                    val altDoc = runCatching { fetchSeriesDocShared(altUrl, searchUrl) }.getOrNull()
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

        val targetReso = targetQualities.firstOrNull()?.label ?: "720p"
        val epUrlWithReso = if (epUrl.contains("reso=")) epUrl else {
            val sep = if (epUrl.contains("?")) "&" else "?"
            "$epUrl${sep}reso=$targetReso"
        }
        val epDoc = runCatching { fetchDoc(epUrlWithReso, bestAnimeUrl) }.getOrElse {
            if (!Thread.currentThread().isInterrupted) SeriesEpisodeMapCache.invalidate(priority, bestAnimeUrl)
            throw it
        }

        val results = mutableListOf<RawMkvCandidate>()
        val seenKeys = hashSetOf<String>()
        val qDefault = MkvQuality.fromText(targetReso) ?: MkvQuality.P720

        harvestYlnimePageCandidates(epDoc, epUrlWithReso, qDefault, targetQualities, seenKeys, results)

        // Jika halaman YLnime memiliki tab resolusi (?reso=480p, ?reso=720p, ?reso=1080p) yang belum terwakili di results,
        // panen tab resolusi pendamping secara paralel cepat (batas 1.5 dtk) agar 480p-1080p lengkap.
        val coveredQualities = results.mapTo(HashSet()) { it.quality }
        val resoTabMap = linkedMapOf<MkvQuality, String>()
        epDoc.select("a[href*='reso=']").forEach { a ->
            val href = a.absUrl("href").trim()
            if (!href.startsWith("http")) return@forEach
            val q = MkvQuality.fromText("${a.text()} $href") ?: return@forEach
            if (q in targetQualities && q !in coveredQualities && q != MkvQuality.P360) {
                resoTabMap.putIfAbsent(q, href)
            }
        }
        if (resoTabMap.isNotEmpty() && !Thread.currentThread().isInterrupted) {
            val cs = ExecutorCompletionService<Pair<MkvQuality, Pair<String, Document>>?>(OtakudesuSourceProvider.mirrorAjaxPool)
            val futures = resoTabMap.entries.take(2).map { (q, tabUrl) ->
                cs.submit {
                    runCatching {
                        q to (tabUrl to fetchDoc(tabUrl, epUrlWithReso))
                    }.getOrNull()
                }
            }
            try {
                val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500L)
                repeat(futures.size) {
                    val remNs = deadlineNs - System.nanoTime()
                    if (remNs <= 0L || Thread.currentThread().isInterrupted) return@repeat
                    val fut = runCatching { cs.poll(remNs, TimeUnit.NANOSECONDS) }.getOrNull() ?: return@repeat
                    val pair = runCatching { fut.get() }.getOrNull() ?: return@repeat
                    val (tabQ, urlAndDoc) = pair
                    harvestYlnimePageCandidates(urlAndDoc.second, urlAndDoc.first, tabQ, targetQualities, seenKeys, results)
                }
            } finally {
                futures.forEach { if (!it.isDone) it.cancel(true) }
            }
        }

        // Fallback WebView EmbedMediaSniffer untuk player HTML5 YLnime yang memuat stream via JavaScript
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

    private fun harvestYlnimePageCandidates(
        epDoc: Document,
        pageUrl: String,
        qDefault: MkvQuality,
        targetQualities: Set<MkvQuality>,
        seenKeys: MutableSet<String>,
        results: MutableList<RawMkvCandidate>
    ) {
        // 1. Pemutar HTML5 <video><source src="..." size="720" label="720p"> (server Animeverse YLnime)
        val fallbackVideoCandidates = mutableListOf<RawMkvCandidate>()
        epDoc.select("video source[src], video[src]").forEach { el ->
            val src = el.absUrl("src").trim()
            if (!src.startsWith("http") || HosterFilter.isBlacklisted("Animeverse", src)) return@forEach
            val q = inferElementQuality(el, el.attr("label"), src, qDefault)
            val key = "${q.label}|$src"
            if (!seenKeys.add(key)) return@forEach
            val isDirectCdn = src.contains(".animeverse.id/", ignoreCase = true) ||
                src.contains(".animekita.org/", ignoreCase = true) ||
                src.contains("pixeldrain.", ignoreCase = true)
            val cand = RawMkvCandidate(
                source = priority,
                quality = q,
                hosterLabel = "Animeverse",
                rawUrl = src,
                pageReferer = pageUrl,
                container = if (src.contains(".m3u8", ignoreCase = true)) MediaContainer.HLS else MediaContainer.MP4,
                isStreamEmbed = !isDirectCdn
            )
            if (q in targetQualities) {
                results.add(cand)
            } else {
                fallbackVideoCandidates.add(cand)
            }
        }
        if (results.isEmpty() && fallbackVideoCandidates.isNotEmpty()) {
            results.addAll(fallbackVideoCandidates)
        }

        // 1b. Ekstrak URL video/Pixeldrain dari atribut data-src/data-video/data-url dan inline <script> YLnime
        epDoc.select("[data-src], [data-video], [data-url]").forEach { el ->
            val raw = el.attr("data-src").ifBlank { el.attr("data-video") }.ifBlank { el.attr("data-url") }.trim()
            if (raw.startsWith("http") && !HosterFilter.isBlacklisted("Animeverse", raw)) {
                val low = raw.lowercase(Locale.ROOT)
                if (low.contains(".mp4") || low.contains(".m3u8") || low.contains("pixeldrain.")) {
                    val q = inferElementQuality(el, el.text(), raw, qDefault)
                    val key = "${q.label}|$raw"
                    if (seenKeys.add(key)) {
                        val isDirect = low.contains("pixeldrain.") || low.contains(".animeverse.id/") || low.contains(".animekita.org/")
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = q,
                                hosterLabel = if (low.contains("pixeldrain.")) "Pixeldrain" else "Animeverse",
                                rawUrl = raw,
                                pageReferer = pageUrl,
                                container = if (low.contains(".m3u8")) MediaContainer.HLS else MediaContainer.MP4,
                                isStreamEmbed = !isDirect
                            )
                        )
                    }
                }
            }
        }
        val streamsJsonRe = Regex("""(?:const|let|var)\s+streams\s*=\s*(\[[\s\S]*?\])\s*;""")
        val scriptMediaRe = Regex(
            """["'](https?://(?:[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?|(?:www\.)?pixeldrain\.(?:com|net|lu)/(?:u|api/file)/[A-Za-z0-9_-]+(?:\?[^"'\s<>]*)?))["']""",
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
                        val link = obj.optString("link").replace("\\/", "/").replace(" ", "%20").trim()
                        if (!link.startsWith("http")) continue
                        val low = link.lowercase(Locale.ROOT)
                        val hostLabel = when {
                            low.contains("pixeldrain.") -> "Pixeldrain"
                            low.contains("animekita.") -> "AnimeKita"
                            else -> "Animeverse"
                        }
                        if (HosterFilter.isBlacklisted(hostLabel, link)) continue
                        val q = MkvQuality.fromText(obj.optString("reso"))
                            ?: MkvQuality.fromText(obj.optString("quality"))
                            ?: MkvQuality.fromText(obj.optString("label"))
                            ?: MkvQuality.fromText(link)
                            ?: qDefault
                        val key = "${q.label}|$link"
                        if (!seenKeys.add(key)) continue
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = q,
                                hosterLabel = hostLabel,
                                rawUrl = link,
                                pageReferer = pageUrl,
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
                if (raw.startsWith("http") && !HosterFilter.isBlacklisted("Animeverse", raw)) {
                    val low = raw.lowercase(Locale.ROOT)
                    val q = MkvQuality.fromText(raw) ?: qDefault
                    val key = "${q.label}|$raw"
                    if (seenKeys.add(key)) {
                        results.add(
                            RawMkvCandidate(
                                source = priority,
                                quality = q,
                                hosterLabel = if (low.contains("pixeldrain.")) "Pixeldrain" else "Animeverse",
                                rawUrl = raw,
                                pageReferer = pageUrl,
                                container = if (low.contains(".m3u8")) MediaContainer.HLS else MediaContainer.MP4,
                                isStreamEmbed = false
                            )
                        )
                    }
                }
            }
        }

        // 2. Tautan Pixeldrain & hoster cepat langsung di halaman YLnime dengan deteksi resolusi konteks elemen
        val fallbackAdded = harvestFallbackCandidates(epDoc, pageUrl, results, qDefault)
        for (c in fallbackAdded) {
            if (seenKeys.add("${c.quality.label}|${c.rawUrl}")) {
                results.add(c)
            }
        }
    }
}

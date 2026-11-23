package id.my.milkglass.source

import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.net.IsolatedHttpClients
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements
import java.util.Locale

internal object StreamingFirstGuards {

    fun isWafBlockedHtml(html: String): Boolean {
        if (html.length > 2_000) return false
        val low = html.lowercase(Locale.ROOT)
        return low.contains("your request was blocked") ||
            low.contains("access denied") ||
            low.contains("error 1020") ||
            low.contains("attention required! | cloudflare")
    }

    fun seasonAndPartDelta(pq: ParsedTitle, pc: ParsedTitle): Double {
        var delta = 0.0
        delta += when {
            pq.season != null && pc.season == pq.season -> 0.25
            pq.season == 1 && pc.season == null -> 0.10
            pq.season != null && pq.season > 1 && pc.season != pq.season -> -0.90
            (pq.season == null || pq.season == 1) && pc.season != null && pc.season > 1 -> -0.90
            else -> 0.0
        }
        delta += when {
            pq.part != null && pc.part == pq.part -> 0.15
            pq.part != null && pc.part != null && pq.part != pc.part -> -0.55
            (pq.part == null || pq.part == 1) && pc.part != null && pc.part > 1 -> -0.55
            else -> 0.0
        }
        return delta
    }

    fun harvestOtakudesuDefaultEmbeds(
        epDoc: Document,
        epUrl: String,
        targetQualities: Set<MkvQuality>,
        seenUrls: MutableSet<String>
    ): List<RawMkvCandidate> {
        val out = mutableListOf<RawMkvCandidate>()
        val defaultMirror = epDoc.selectFirst("a[data-default=true]")
        val defaultUlClass = defaultMirror?.parents()?.firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
        val defaultLabel = defaultMirror?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "OtakudesuEmbed"
        for (ifr in epDoc.select("#embed_holder iframe[src], .responsive-embed-stream iframe[src], .player-embed iframe[src], .venkonten iframe[src]")) {
            val src = ifr.absUrl("src").trim()
            val low = src.lowercase(Locale.ROOT)
            if (src.startsWith("http") && !HosterFilter.isBlacklisted(defaultLabel, src) &&
                !low.contains("disqus") && !low.contains("facebook") && !low.contains("youtube.com") &&
                seenUrls.add(src)
            ) {
                val q = MkvQuality.fromText(src) ?: MkvQuality.fromText(defaultUlClass) ?: MkvQuality.P720
                if (q in targetQualities) {
                    out.add(
                        RawMkvCandidate(
                            source = SourcePriority.OTAKUDESU,
                            quality = q,
                            hosterLabel = defaultLabel,
                            rawUrl = src,
                            pageReferer = epUrl,
                            container = MediaContainer.MP4,
                            isStreamEmbed = true
                        )
                    )
                }
            }
        }
        return out
    }

    fun shouldFetchOtakudesuMirrors(
        defaultEmbeds: List<RawMkvCandidate>,
        mirrorLinks: Elements,
        targetQualities: Set<MkvQuality>,
        deadOrAdMirrorTokens: List<String>
    ): Boolean {
        val hasHealthyDefault = defaultEmbeds.any { !it.rawUrl.contains("nekocloud", ignoreCase = true) }
        if (!hasHealthyDefault) return mirrorLinks.isNotEmpty()
        val wantsHd = MkvQuality.P720 in targetQualities || MkvQuality.P1080 in targetQualities
        if (!wantsHd) return false
        val alreadyHasHd = defaultEmbeds.any {
            (it.quality == MkvQuality.P720 || it.quality == MkvQuality.P1080) &&
                !it.rawUrl.contains("nekocloud", ignoreCase = true)
        }
        if (alreadyHasHd) return false
        return mirrorLinks.any { a ->
            val label = a.text().trim()
            val lowLabel = label.lowercase(Locale.ROOT)
            if (HosterFilter.isBlacklisted(label, label) || deadOrAdMirrorTokens.any { lowLabel.contains(it) }) {
                return@any false
            }
            val ulClass = a.parents().firstOrNull { it.tagName() == "ul" }?.className().orEmpty()
            val q = MkvQuality.fromText(ulClass) ?: MkvQuality.P720
            q in targetQualities && (q == MkvQuality.P720 || q == MkvQuality.P1080)
        }
    }

    /**
     * Jika embed desustream/odstream membungkus nested iframe (mis. blogger.com / ondesu / googlevideo),
     * telusuri langsung lewat HTTP ringan agar tidak perlu menunggu cold-boot WebView.
     */
    fun unwrapNestedEmbedUrl(clients: IsolatedHttpClients, url: String, referer: String?): String? {
        val low = url.lowercase(Locale.ROOT)
        if (!low.contains("desustream.") && !low.contains("odstream.xyz")) return null
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            clients.sourceClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string().orEmpty()
                if (html.isBlank()) return@use null
                val doc = Jsoup.parse(html, url)
                doc.select("iframe[src]").firstNotNullOfOrNull { el ->
                    val src = el.absUrl("src").trim()
                    val sLow = src.lowercase(Locale.ROOT)
                    src.takeIf {
                        it.startsWith("http") &&
                            it != url &&
                            (sLow.contains("blogger.com") || sLow.contains("ondesu") ||
                                sLow.contains("googlevideo.com") || sLow.contains("desustream") ||
                                sLow.contains("filedon") || sLow.contains("odcdn") || sLow.contains("otakuwatch") ||
                                sLow.contains("filemoon"))
                    }
                }
            }
        }.getOrNull()
    }

    private val httpOnlyEmbedTokens = listOf(
        "odstream", "odcdn", "desustream", "ondesu", "blogger.com",
        "otakuwatch", "icefile.web.id", "filedon.co", "odfiles.com",
        "streamruby", "rubyvidhub", "filemoon"
    )

    /**
     * Skema Zero-WebView First (target play 1-2 detik):
     * - Tier 0: Direct-API Hoster / Direct Media (Pixeldrain, Filedon, Buzzheavier, GoFile, .mp4/.m3u8) -> 0 WebView (0.6-1.5s)
     * - Tier 1: HTTP-only Stream Embed (odstream, odcdn, blogger, desustream) -> 0 WebView (1.0-1.8s)
     * - Tier 2: Hoster non-embed generik lainnya
     * - Tier 3: Embed berbasis WebView sniffer (YLnimePlayer / player JS berat) -> cadangan terakhir (2.8-6.8s)
     */
    fun zeroWebViewTier(c: RawMkvCandidate): Int {
        val lowLabel = c.hosterLabel.lowercase(Locale.ROOT)
        val lowUrl = c.rawUrl.lowercase(Locale.ROOT)
        val isWebViewPlayer = lowLabel == "ylnimeplayer" ||
            (lowUrl.contains("ylnime.com/index.php") && !lowUrl.contains(".mp4") && !lowUrl.contains(".m3u8"))
        if (isWebViewPlayer) return 3
        if (HosterFilter.isFastFallbackHoster(c.hosterLabel, c.rawUrl)) return 0
        if (c.isStreamEmbed) {
            return if (httpOnlyEmbedTokens.any { lowLabel.contains(it) || lowUrl.contains(it) }) 1 else 3
        }
        return 2
    }

    fun rankForPlayback(
        candidates: List<RawMkvCandidate>,
        qualityOrder: List<MkvQuality>,
        allowBatchFallback: Boolean,
        preferCompatibleContainer: Boolean,
        preferStreamingEmbeds: Boolean
    ): List<RawMkvCandidate> {
        fun containerRank(c: MediaContainer): Int {
            return if (preferCompatibleContainer || preferStreamingEmbeds) {
                when (c) {
                    MediaContainer.HLS -> 0
                    MediaContainer.MP4 -> 1
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
                compareBy<RawMkvCandidate> { zeroWebViewTier(it) }
                    .thenBy { qualityOrder.indexOf(it.quality).let { idx -> if (idx < 0) 99 else idx } }
                    .thenBy { containerRank(it.container) }
                    .thenByDescending { EndpointHealthScorer.score(it.hosterLabel) }
                    .thenBy { HosterFilter.rank(it.hosterLabel, it.rawUrl) }
            )
    }

    fun shouldDeferWebViewSniffer(batch: List<RawMkvCandidate>): Boolean =
        batch.any { zeroWebViewTier(it) <= 1 }

    fun selectExtractionBatch(pool: List<RawMkvCandidate>): List<RawMkvCandidate> {
        val tier0Direct = pool.filter { zeroWebViewTier(it) == 0 }.take(5)
        val tier1HttpEmbeds = pool.filter { zeroWebViewTier(it) == 1 }.take(3)
        val head = (tier0Direct + tier1HttpEmbeds).distinctBy { it.rawUrl }
        val remainingSlots = (10 - head.size).coerceAtLeast(0)
        val seen = head.mapTo(HashSet()) { it.rawUrl }
        val tail = pool.filter { it.rawUrl !in seen }.take(remainingSlots)
        return head + tail
    }
}

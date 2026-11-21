package id.my.milkglass.source

import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.net.IsolatedHttpClients
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
        for (ifr in epDoc.select("#embed_holder iframe[src], .responsive-embed-stream iframe[src], .player-embed iframe[src], .venkonten iframe[src]")) {
            val src = ifr.absUrl("src").trim()
            val low = src.lowercase(Locale.ROOT)
            if (src.startsWith("http") && !HosterFilter.isBlacklisted("OtakudesuEmbed", src) &&
                !low.contains("disqus") && !low.contains("facebook") && !low.contains("youtube.com") &&
                seenUrls.add(src)
            ) {
                val q = MkvQuality.fromText(src) ?: MkvQuality.P720
                if (q in targetQualities) {
                    out.add(
                        RawMkvCandidate(
                            source = SourcePriority.OTAKUDESU,
                            quality = q,
                            hosterLabel = "OtakudesuEmbed",
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
                                sLow.contains("vidhide") || sLow.contains("filemoon") || sLow.contains("yourupload"))
                    }
                }
            }
        }.getOrNull()
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
                compareBy<RawMkvCandidate> { if (preferStreamingEmbeds && it.isStreamEmbed) 0 else 1 }
                    .thenBy { qualityOrder.indexOf(it.quality).let { idx -> if (idx < 0) 99 else idx } }
                    .thenBy { containerRank(it.container) }
                    .thenByDescending { EndpointHealthScorer.score(it.hosterLabel) }
                    .thenBy { HosterFilter.rank(it.hosterLabel, it.rawUrl) }
            )
    }

    fun selectExtractionBatch(pool: List<RawMkvCandidate>): List<RawMkvCandidate> {
        val prioritizedEmbeds = pool.filter { it.isStreamEmbed }.take(4)
        val remainingSlots = (8 - prioritizedEmbeds.size).coerceAtLeast(0)
        return (prioritizedEmbeds + pool.filterNot { it.isStreamEmbed }.take(remainingSlots)).distinctBy { it.rawUrl }
    }
}

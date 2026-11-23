package id.my.milkglass

import id.my.milkglass.extractor.EmbedMediaSniffer
import id.my.milkglass.extractor.MkvHosterExtractorRegistry
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.MediaContainer
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.MkvSourceProvider
import id.my.milkglass.source.RawCandidateStore
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.SourceCircuitBreaker
import id.my.milkglass.source.SeriesEpisodeMapCache
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.StreamingFirstGuards
import id.my.milkglass.source.TitleMatcher
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantPlayReliabilityTest {

    private class SleepyProvider(override val priority: SourcePriority, private val sleepMs: Long) : MkvSourceProvider {
        override fun findEpisodeMkvCandidates(
            animeTitle: String,
            episodeNumber: Int,
            targetQualities: Set<MkvQuality>
        ): List<RawMkvCandidate> {
            Thread.sleep(sleepMs)
            return emptyList()
        }
    }

    @Test
    fun rawCandidatesSurviveEncodeDecodeAndInvalidEntriesAreDropped() {
        val original = listOf(
            RawMkvCandidate(
                SourcePriority.OTAKUDESU, MkvQuality.P1080, "Pixeldrain",
                "https://pixeldrain.com/u/abc123", "https://otakudesu.blog/episode/x", "320 MB", false, MediaContainer.MKV
            ),
            RawMkvCandidate(
                SourcePriority.YLNIME, MkvQuality.P720, "Krakenfiles",
                "https://krakenfiles.com/view/zzz/file.html", "https://ylnime.com/x", null, false, MediaContainer.MP4
            )
        )
        val arr = RawCandidateStore.encodeList(original)
        // entri rusak harus dilewati, bukan menjatuhkan seluruh cache
        arr.put(JSONObject().put("s", "tidak-ada").put("q", "1080p").put("u", "https://x.test/a"))
        arr.put(JSONObject().put("s", "otakudesu").put("q", "1080p").put("u", "javascript:alert(1)"))
        val decoded = RawCandidateStore.decodeList(JSONArray(arr.toString()))
        assertEquals(original, decoded)
    }

    @Test
    fun cancelledResolveNeverOpensCircuitBreaker() {
        SourceCircuitBreaker.resetAll()
        val resolver = MkvCascadeResolver(
            clients = IsolatedHttpClients(null),
            providers = listOf(
                SleepyProvider(SourcePriority.OTAKUDESU, 8_000),
                SleepyProvider(SourcePriority.YLNIME, 8_000)
            )
        )
        // Lebih dari FAIL_THRESHOLD (2): dulu setiap pembatalan dihitung sebagai kegagalan sumber.
        repeat(3) { round ->
            var result: CascadeResult? = null
            val t = Thread { result = resolver.resolve("judul uji $round", 1) }
            t.start()
            Thread.sleep(1_800) // lewati HEDGE_DELAY (1,2 dtk) supaya kedua provider sudah jalan
            t.interrupt()
            t.join(5_000)
            assertFalse("resolve harus berhenti setelah interupsi", t.isAlive)
            val r = result
            assertNotNull(r)
            assertTrue(r is CascadeResult.Failure)
            assertEquals("Dibatalkan", (r as CascadeResult.Failure).reason)
        }
        assertFalse(SourceCircuitBreaker.isOpen(SourcePriority.OTAKUDESU))
        assertFalse(SourceCircuitBreaker.isOpen(SourcePriority.YLNIME))
    }

    @Test
    fun parseLiveEpisodeMapDetectsNewlyReleasedEpisodeAndEpisodeZero() {
        val ylHtml = """
            <a href="index.php?series=mushoku-tensei&episode=al-24930-0">Episode 0</a>
            <a href="index.php?series=mushoku-tensei&episode=al-24930-1">Episode 1</a>
            <a href="index.php?series=mushoku-tensei&episode=al-24930-13">Episode 13</a>
        """.trimIndent()
        val ylMap = EngineRuntime.parseLiveEpisodeMap(SourcePriority.YLNIME, ylHtml, "https://ylnime.com/index.php?series=mushoku-tensei")
        assertTrue(0 in ylMap)
        assertEquals(13, ylMap.keys.maxOrNull())
        assertEquals("https://ylnime.com/index.php?series=mushoku-tensei&episode=al-24930-13", ylMap[13])

        val otakuHtml = """
            <div class="episodelist"><ul><li><a href="https://otakudesu.blog/batch/test/">Batch</a></li></ul></div>
            <div class="episodelist">
              <ul>
                <li><a href="https://otakudesu.blog/episode/test-episode-14-sub-indo/">Test Episode 14 Sub Indo</a></li>
                <li><a href="https://otakudesu.blog/episode/test-episode-1-sub-indo/">Test Episode 1 Sub Indo</a></li>
              </ul>
            </div>
        """.trimIndent()
        val otakuMap = EngineRuntime.parseLiveEpisodeMap(SourcePriority.OTAKUDESU, otakuHtml, "https://otakudesu.blog/anime/test/")
        assertEquals(14, otakuMap.keys.maxOrNull())
        assertEquals("https://otakudesu.blog/episode/test-episode-14-sub-indo/", otakuMap[14])
    }

    @Test
    fun ylnimeMovieEpisodeLinkIsRecognizedEvenWithMultipleAnchors() {
        val epUrl = "https://ylnime.com/index.php?series=date-a-live-movie-mayuri-judgment&episode=al-149330-1"
        assertEquals(1, SeriesEpisodeMapCache.extractEpisodeNumber("Episode Movie", epUrl))
        assertTrue(TitleMatcher.matchesEpisode("Episode Movie", epUrl, 1))

        val html = """
            <a href="index.php?series=date-a-live-movie-mayuri-judgment&episode=al-149330-1">Tonton Sekarang</a>
            <a href="index.php?series=date-a-live-movie-mayuri-judgment&episode=al-149330-1&reso=720p">Episode Movie</a>
        """.trimIndent()
        val parsed = EngineRuntime.parseLiveEpisodeMap(
            SourcePriority.YLNIME,
            html,
            "https://ylnime.com/index.php?series=date-a-live-movie-mayuri-judgment"
        )
        assertEquals(epUrl, parsed[1])
    }

    @Test
    fun embedMediaSnifferAcceptsYlnimeDomProxyVideoAndRangeBackedStreams() {
        assertTrue(EmbedMediaSniffer.isDomVideoElementSourceUrl("https://ylnime.com/stream.php?id=al-149330-1&reso=720p"))
        assertTrue(EmbedMediaSniffer.isRangeBackedMediaRequest(
            "https://cdn.animeverse.example/video/149330",
            mapOf("Range" to "bytes=0-65535")
        ))
        assertFalse(EmbedMediaSniffer.isDomVideoElementSourceUrl("https://doubleclick.net/ads/video.mp4"))
        assertFalse(EmbedMediaSniffer.isDomVideoElementSourceUrl("https://ylnime.com/assets/app.js"))
        assertFalse(EmbedMediaSniffer.isRangeBackedMediaRequest(
            "https://cdn.animeverse.example/video/149330",
            emptyMap()
        ))
    }

    @Test
    fun hybridIdentitySafeTitleMatcherHandlesTyposSwapsRomajiAndLocksSubtitlesAndSeasons() {
        // Lapis 1 & 4: ejaan UK/US + format override (Movie vs TV) + simbol/tag noise
        assertTrue(
            TitleMatcher.score("Date A Live Movie: Mayuri Judgement", "Date A Live: Mayuri Judgment (Sub Indo)") >= 0.85
        )
        assertTrue(
            TitleMatcher.score("Date A Bullet: Nightmare or Queen", "Date A Bullet - Nightmare or Queen [BD]") >= 0.85
        )
        // Lapis 1: normalisasi Romaji (ou -> o)
        assertTrue(
            TitleMatcher.score("Toukyou Ghoul", "Tokyo Ghoul [Sub Indo]") >= 0.85
        )
        // Lapis 2: urutan kata/subjudul dibalik tetap cocok
        assertTrue(
            TitleMatcher.score("Mayuri Judgment: Date A Live Movie", "Date A Live Movie: Mayuri Judgment") >= 0.80
        )
        // Lapis 3: kunci identitas subjudul (Dead or Bullet tidak boleh tertukar Nightmare or Queen)
        assertTrue(
            TitleMatcher.score("Date A Bullet: Dead or Bullet", "Date A Bullet: Nightmare or Queen") < 0.30
        )
        // Lapis 3: kunci identitas season & spin-off & TV vs Movie tanpa subjudul
        assertTrue(
            TitleMatcher.score("Date A Live", "Date A Live IV") < 0.40
        )
        assertTrue(
            TitleMatcher.score("Date A Live", "Date A Bullet: Dead or Bullet") < 0.40
        )
        assertTrue(
            TitleMatcher.score("Date A Live", "Date A Live Movie: Mayuri Judgment") < 0.40
        )
        // Lapis 4: proteksi episode desimal recap (12.5 tidak membajak 12)
        assertEquals(null, SeriesEpisodeMapCache.extractEpisodeNumber("Episode 12.5", "https://example.com/ep/12.5"))
        assertFalse(TitleMatcher.matchesEpisode("Episode 12.5", "https://example.com/ep/12.5", 12))
        assertTrue(TitleMatcher.matchesEpisode("Episode 12", "https://example.com/ep/12", 12))
    }

    @Test
    fun otakudesuDefault480pEmbedDoesNotBlock720pMirrorFetch() {
        val html = """
            <div id="embed_holder">
              <iframe src="https://desustream.info/dstream/odcdn/v2/index.php?id=abc123"></iframe>
            </div>
            <ul class="m480p">
              <li><a href="#" data-content="eyJpZCI6MSwiaSI6MCwicSI6IjQ4MHAifQ==" data-default="true">odcdn</a></li>
            </ul>
            <ul class="m720p">
              <li><a href="#" data-content="eyJpZCI6MSwiaSI6MCwicSI6IjcyMHAifQ==">odstream</a></li>
              <li><a href="#" data-content="eyJpZCI6MSwiaSI6MSwicSI6IjcyMHAifQ==">vidhide</a></li>
            </ul>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://otakudesu.blog/episode/knkr-episode-8-sub-indo/")
        val seen = hashSetOf<String>()
        val target720 = setOf(MkvQuality.P1080, MkvQuality.P720, MkvQuality.P480)
        val defaults = StreamingFirstGuards.harvestOtakudesuDefaultEmbeds(
            doc,
            "https://otakudesu.blog/episode/knkr-episode-8-sub-indo/",
            target720,
            seen
        )
        assertEquals(1, defaults.size)
        assertEquals(MkvQuality.P480, defaults.first().quality)
        assertEquals("odcdn", defaults.first().hosterLabel)

        val mirrors = doc.select("ul.m360p li a[data-content], ul.m480p li a[data-content], ul.m720p li a[data-content]")
        val deadTokens = listOf("vidhide", "ondesuhd", "moedesu", "nekocloud", "mega", "yourupload")
        assertTrue(
            StreamingFirstGuards.shouldFetchOtakudesuMirrors(defaults, mirrors, setOf(MkvQuality.P1080, MkvQuality.P720), deadTokens)
        )
        assertFalse(
            StreamingFirstGuards.shouldFetchOtakudesuMirrors(defaults, mirrors, setOf(MkvQuality.P480), deadTokens)
        )
    }

    @Test
    fun otakudesuOvaOrSpecialNeverHijacksMainEpisodeOneAndStandaloneOvaNumberedLinksResolve() {
        val otakuHtmlWithSpecialAtTop = """
            <div class="episodelist">
              <ul>
                <li><a href="https://otakudesu.blog/episode/durara-special-episode-1-sub-indo/">Durarara!! Special Episode 1 Sub Indo</a></li>
                <li><a href="https://otakudesu.blog/episode/durara-ova-2-sub-indo/">Durarara!! OVA 2 (25) Subtitle Indonesia</a></li>
                <li><a href="https://otakudesu.blog/episode/durara-episode-2-sub-indo/">Durarara!! Episode 2 Subtitle Indonesia</a></li>
                <li><a href="https://otakudesu.blog/episode/durara-episode-1-sub-indo/">Durarara!! Episode 1 Subtitle Indonesia</a></li>
              </ul>
            </div>
        """.trimIndent()
        val parsed = EngineRuntime.parseLiveEpisodeMap(
            SourcePriority.OTAKUDESU,
            otakuHtmlWithSpecialAtTop,
            "https://otakudesu.blog/anime/durara-sub-indo/"
        )
        assertEquals("https://otakudesu.blog/episode/durara-episode-1-sub-indo/", parsed[1])
        assertEquals("https://otakudesu.blog/episode/durara-episode-2-sub-indo/", parsed[2])

        // Standalone OVA series tetap terbaca nomor OVA-nya
        assertEquals(1, SeriesEpisodeMapCache.extractEpisodeNumber("Durarara!! OVA 1", "https://otakudesu.blog/episode/rrrda-ova-1-sub-indo/"))
        assertEquals(2, SeriesEpisodeMapCache.extractEpisodeNumber("Durarara!! OVA 2", "https://otakudesu.blog/episode/rrrda-ova-2-sub-indo/"))
        assertTrue(TitleMatcher.score("Magical Explorer", "Magical★Explorer Subtitle Indonesia") >= 0.85)
    }

    @Test
    fun filedonParserRejectsEmbedForbiddenAndExtractsSignedR2Url() {
        val registry = MkvHosterExtractorRegistry(IsolatedHttpClients(null))
        val forbiddenHtml = """
            <div id="app" data-page='{"component":"public\/embed-forbidden","props":{"message":"This embed is not allowed on this website."}}'></div>
        """.trimIndent()
        assertEquals(null, registry.parseFiledonHtml(forbiddenHtml))

        val validHtml = """
            <div id="app" data-page='{"component":"public\/embed","props":{"url":"https:\/\/cdn.r2.example\/filedon\/knkr-08.mkv"}}'></div>
        """.trimIndent()
        val extracted = registry.parseFiledonHtml(validHtml)
        assertNotNull(extracted)
        assertEquals("https://cdn.r2.example/filedon/knkr-08.mkv", extracted!!.directUrl)
        assertTrue(extracted.supportsRange)
    }

    @Test
    fun bloggerParserUnescapesHtmlEntitiesAndPrefers720pItag22Over360pItag18() {
        val registry = MkvHosterExtractorRegistry(IsolatedHttpClients(null))
        val rawHtml = """
            <script>
            var cfg = [null,[[&quot;https:\u002f\u002frr5.googlevideo.com\u002fvideoplayback?id=abc\u0026itag=18\u0026source=blogger&quot;,null,1],[&quot;https:\u002f\u002frr5.googlevideo.com\u002fvideoplayback?id=abc\u0026itag=22\u0026source=blogger&quot;,null,2]]];
            </script>
        """.trimIndent()
        val extracted = registry.parseBloggerHtml(rawHtml)
        assertNotNull(extracted)
        assertEquals("https://rr5.googlevideo.com/videoplayback?id=abc&itag=22&source=blogger", extracted!!.directUrl)
        assertFalse(extracted.directUrl.contains("&quot;"))
        assertEquals("https://youtube.googleapis.com/", extracted.referer)
    }

    @Test
    fun zeroWebViewFirstRanksDirectApiHostersAndHttpEmbedsAheadOfWebViewSniffer() {
        val webViewCand = RawMkvCandidate(
            SourcePriority.YLNIME, MkvQuality.P720, "YLnimePlayer",
            "https://ylnime.com/index.php?series=spy-family&episode=al-1-1&reso=720p",
            "https://ylnime.com/", null, false, MediaContainer.MP4, isStreamEmbed = true
        )
        val httpEmbedCand = RawMkvCandidate(
            SourcePriority.OTAKUDESU, MkvQuality.P720, "odstream",
            "https://desustream.info/dstream/odstream/v2/index.php?id=abc",
            "https://otakudesu.blog/", null, false, MediaContainer.MP4, isStreamEmbed = true
        )
        val pixeldrainCand = RawMkvCandidate(
            SourcePriority.OTAKUDESU, MkvQuality.P720, "Pixeldrain",
            "https://pixeldrain.com/u/zoxSe2yx",
            "https://otakudesu.blog/", null, false, MediaContainer.MP4, isStreamEmbed = false
        )
        val ranked = StreamingFirstGuards.rankForPlayback(
            listOf(webViewCand, httpEmbedCand, pixeldrainCand),
            MkvQuality.ALL_DESC,
            allowBatchFallback = false,
            preferCompatibleContainer = true,
            preferStreamingEmbeds = true
        )
        assertEquals(listOf("Pixeldrain", "odstream", "YLnimePlayer"), ranked.map { it.hosterLabel })
        assertEquals(0, StreamingFirstGuards.zeroWebViewTier(pixeldrainCand))
        assertEquals(1, StreamingFirstGuards.zeroWebViewTier(httpEmbedCand))
        assertEquals(3, StreamingFirstGuards.zeroWebViewTier(webViewCand))
    }

    @Test
    fun subSecondOptimizationTrustsR2AndDefersWebViewWhenZeroWebViewExists() {
        assertTrue(
            EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(
                "https://4ee42e7b34bf3c2aec4681b9c17de08f.r2.cloudflarestorage.com/filedon/ep8.mkv?X-Amz-Signature=abc"
            )
        )
        assertTrue(
            EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(
                "https://pixeldrain.com/api/file/zoxSe2yx"
            )
        )
        val webViewOnly = listOf(
            RawMkvCandidate(
                SourcePriority.YLNIME, MkvQuality.P720, "YLnimePlayer",
                "https://ylnime.com/index.php?series=x&episode=1",
                "https://ylnime.com/", null, false, MediaContainer.MP4, isStreamEmbed = true
            )
        )
        val withPixeldrain = webViewOnly + RawMkvCandidate(
            SourcePriority.OTAKUDESU, MkvQuality.P720, "Pixeldrain",
            "https://pixeldrain.com/u/zoxSe2yx",
            "https://otakudesu.blog/", null, false, MediaContainer.MP4, isStreamEmbed = false
        )
        assertFalse(StreamingFirstGuards.shouldDeferWebViewSniffer(webViewOnly))
        assertTrue(StreamingFirstGuards.shouldDeferWebViewSniffer(withPixeldrain))
    }

    @Test
    fun ylnimeDirectS3AndPixeldrainApiAreTrustedZeroWebViewEndpoints() {
        assertTrue(
            EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(
                "https://s3.animeverse.id/ro/ffa8-1791046151587.mp4"
            )
        )
        assertTrue(
            EmbedMediaSniffer.isTrustedBinaryStreamEndpoint(
                "https://pixeldrain.com/api/file/wF9Lj6Ad?download"
            )
        )
        val s3Cand = RawMkvCandidate(
            SourcePriority.YLNIME, MkvQuality.P720, "Animeverse",
            "https://s3.animeverse.id/ro/ffa8-1791046151587.mp4",
            "https://ylnime.com/", null, false, MediaContainer.MP4, isStreamEmbed = false
        )
        assertEquals(0, StreamingFirstGuards.zeroWebViewTier(s3Cand))
    }
}

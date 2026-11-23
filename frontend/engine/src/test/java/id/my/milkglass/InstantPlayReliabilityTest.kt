package id.my.milkglass

import id.my.milkglass.extractor.EmbedMediaSniffer
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
import id.my.milkglass.source.TitleMatcher
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
}

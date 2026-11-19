package id.my.milkglass

import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.download.RetryBackoff
import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.extractor.JsUnpacker
import id.my.milkglass.player.ErrorMapper
import id.my.milkglass.player.PlaybackPhase
import id.my.milkglass.player.PlayerManager
import id.my.milkglass.player.PlayerSnapshot
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.StreamResolutionException
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.IndexedAnimeEntry
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.SourceAvailabilityIndex
import id.my.milkglass.source.SourceCircuitBreaker
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.SourceSlugCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingEndToEndTest {

    @Test
    fun canonicalBackendEndpointsAndTrustedHostsAreAligned() {
        assertEquals(
            MilkglassEndpoints.DEFAULT_BACKEND_BASE_URL,
            StreamResolver.DEFAULT_BACKEND_BASE_URL
        )
        assertTrue(MilkglassEndpoints.isTrustedBackendHost("milkglass-backend.milkyliquid.workers.dev"))
        assertTrue(MilkglassEndpoints.isTrustedBackendHost("milkglass.my.id"))
        assertTrue(MilkglassEndpoints.isTrustedBackendHost("api.milkglass.my.id"))
        assertFalse(MilkglassEndpoints.isTrustedBackendHost("evil-milkglass.my.id"))
        assertFalse(MilkglassEndpoints.isTrustedBackendHost("example.com"))
    }

    @Test
    fun sourceConfigParserExtractsValidHttpsDomainsOnly() {
        val json = """
            {
              "status": "ok",
              "sources": [
                {"rank": 1, "id": "otakudesu", "base_url": "https://otakudesu.best/"},
                {"rank": 2, "id": "samehadaku", "base_url": "https://v3.samehadaku.how"},
                {"rank": 3, "id": "oploverz", "base_url": "http://insecure.oploverz.ch"},
                {"rank": 4, "id": "kusonime", "base_url": "https://kusonime.com"}
              ]
            }
        """.trimIndent()
        val parsed = SourceConfigStore.parseSourcesJson(json)
        assertEquals("https://otakudesu.best", parsed[SourcePriority.OTAKUDESU])
        assertEquals("https://v3.samehadaku.how", parsed[SourcePriority.SAMEHADAKU])
        assertEquals(null, parsed[SourcePriority.OPLOVERZ])
        assertEquals("https://kusonime.com", parsed[SourcePriority.KUSONIME])
    }


    @Test
    fun remoteClearanceParserExtractsCookiesAndUserAgent() {
        val json = """
            {
              "status": "ok",
              "data": {
                "origin": "https://otakudesu.blog/",
                "cookies": {"cf_clearance": "tok123", "__cf_bm": "bm456"},
                "user_agent": "Mozilla/5.0 Chrome/131.0.0.0"
              }
            }
        """.trimIndent()
        val parsed = EngineRuntime.parseRemoteClearanceJson(json)
        assertNotNull(parsed)
        assertEquals("tok123", parsed!!.cookies["cf_clearance"])
        assertEquals("Mozilla/5.0 Chrome/131.0.0.0", parsed.userAgent)
    }

    @Test
    fun slugBuilderProducesNormalizedEpisodeSlug() {
        val slug = StreamResolver.buildEpisodeSlug("Jujutsu Kaisen Season 3", 5)
        assertEquals("jujutsu-kaisen-season-3-ep-5", slug)
    }

    @Test
    fun sourcePriorityOrderMatchesFourSourceCascade() {
        assertEquals(1, SourcePriority.OTAKUDESU.rank)
        assertEquals(2, SourcePriority.SAMEHADAKU.rank)
        assertEquals(3, SourcePriority.OPLOVERZ.rank)
        assertEquals(4, SourcePriority.KUSONIME.rank)
    }

    @Test
    fun mkvQualityParsesStandardAndSiteAliases() {
        assertEquals(MkvQuality.P1080, MkvQuality.fromText("MKV 1080p"))
        assertEquals(MkvQuality.P1080, MkvQuality.fromText("FULLHD"))
        assertEquals(MkvQuality.P1080, MkvQuality.fromText("FHD"))
        assertEquals(MkvQuality.P720, MkvQuality.fromText("MKV 720p"))
        assertEquals(MkvQuality.P720, MkvQuality.fromText("HD"))
        assertEquals(MkvQuality.P480, MkvQuality.fromText("MKV 480p"))
        assertEquals(MkvQuality.P480, MkvQuality.fromText("Mini"))
    }

    @Test
    fun hosterFilterPrioritizesPixeldrainAndBlocksMega() {
        assertTrue(HosterFilter.isBlacklisted("Mega", "https://mega.nz/file/123"))
        assertTrue(HosterFilter.isBlacklisted("Otakufiles", "https://otakufiles.net/abc"))
        assertFalse(HosterFilter.isBlacklisted("Pdrain", "https://pixeldrain.com/u/abc"))
        assertTrue(
            HosterFilter.rank("Pdrain", "https://pixeldrain.com/u/abc") <
                HosterFilter.rank("KFiles", "https://krakenfiles.com/view/xyz/file.html")
        )
    }

    @Test
    fun jsUnpackerDecodesPackedEmbedScript() {
        val packed = "\u007D('0 1=\"2://3.4/5.6\";',7,7,'var|src|https|cdn|example|ep1|mkv'.split('|'),0,{}))"
        val unpacked = JsUnpacker.unpack(packed)
        assertNotNull(unpacked)
        assertTrue(unpacked!!.contains("https://cdn.example/ep1.mkv"))
    }

    @Test
    fun slugCacheStoresAndInvalidatesInMemory() {
        val cache = SourceSlugCache(null)
        cache.put(SourcePriority.OTAKUDESU, "Jujutsu Kaisen", "https://otakudesu.blog/anime/jjk-sub-indo/")
        assertEquals(
            "https://otakudesu.blog/anime/jjk-sub-indo/",
            cache.get(SourcePriority.OTAKUDESU, "Jujutsu Kaisen")
        )
        cache.invalidate(SourcePriority.OTAKUDESU, "Jujutsu Kaisen")
        assertEquals(null, cache.get(SourcePriority.OTAKUDESU, "Jujutsu Kaisen"))
    }

    @Test
    fun errorMapperPreservesStreamResolutionReason() {
        val err = StreamResolutionException(FailureReason.HTTP_403, "Cloudflare challenge")
        assertEquals(FailureReason.HTTP_403, ErrorMapper.classifyThrowable(err))
    }

    @Test
    fun subtitleSanitizerDropsAssVectorDrawingsAndBlankLines() {
        val raw = "m 0 0 l 235.5 0 l 215.5 108 l 0 10\n\nMemastikan kau tetap hidup\\N\\Ndan bersekolah"
        val lines = PlayerManager.sanitizeSubtitleText(raw)
        assertEquals(listOf("Memastikan kau tetap hidup", "dan bersekolah"), lines)
    }

    @Test
    fun availabilityIndexMatchesPresentTitlesAndRejectsMissingOnes() {
        val index = SourceAvailabilityIndex(null)
        val entries = (1..20).map { i ->
            IndexedAnimeEntry(
                source = SourcePriority.OTAKUDESU,
                title = if (i == 1) "Tantei wa Mou Shindeiru Season 2" else "Sample Anime $i",
                normalizedTitle = if (i == 1) "tantei wa mou shindeiru season 2" else "sample anime $i",
                tokens = if (i == 1) setOf("tantei", "wa", "mou", "shindeiru", "season") else setOf("sample", "anime", "$i"),
                url = "https://otakudesu.blog/anime/sample-$i/"
            )
        }
        index.loadEntriesForTest(entries)
        assertTrue(index.isReady)
        assertTrue(index.isAvailable(listOf("Tantei wa mou, Shindeiru. Season 2")))
        assertFalse(index.isAvailable(listOf("Anime Fiktif Yang Tidak Ada Di Sumber")))
    }

    @Test
    fun circuitBreakerOpensAfterTwoFailuresAndResetsOnSuccess() {
        SourceCircuitBreaker.resetAll()
        assertFalse(SourceCircuitBreaker.isOpen(SourcePriority.SAMEHADAKU, 1000L))
        SourceCircuitBreaker.recordFailure(SourcePriority.SAMEHADAKU, 1000L)
        assertFalse(SourceCircuitBreaker.isOpen(SourcePriority.SAMEHADAKU, 1000L))
        SourceCircuitBreaker.recordFailure(SourcePriority.SAMEHADAKU, 1000L)
        assertTrue(SourceCircuitBreaker.isOpen(SourcePriority.SAMEHADAKU, 2000L))
        SourceCircuitBreaker.recordSuccess(SourcePriority.SAMEHADAKU)
        assertFalse(SourceCircuitBreaker.isOpen(SourcePriority.SAMEHADAKU, 2000L))
    }

    @Test
    fun retryBackoffGrowsExponentiallyWithinBounds() {
        val d0 = RetryBackoff.nextDelayMs(attempt = 0, baseMs = 1000L, maxMs = 10_000L, randomUnit = 0.5)
        val d1 = RetryBackoff.nextDelayMs(attempt = 1, baseMs = 1000L, maxMs = 10_000L, randomUnit = 0.5)
        val d5 = RetryBackoff.nextDelayMs(attempt = 5, baseMs = 1000L, maxMs = 10_000L, randomUnit = 0.5)
        assertEquals(1000L, d0)
        assertEquals(2000L, d1)
        assertEquals(10_000L, d5)
    }

    @Test
    fun endpointHealthScorerPrioritizesHealthyHosters() {
        EndpointHealthScorer.reset()
        EndpointHealthScorer.recordSuccess("Pixeldrain")
        EndpointHealthScorer.recordFailure("KrakenFiles")
        assertTrue(EndpointHealthScorer.score("Pixeldrain") > EndpointHealthScorer.score("KrakenFiles"))
    }

    @Test
    fun playerSnapshotPhasesAreDeterministic() {
        val loading = PlayerSnapshot(phase = PlaybackPhase.LOADING)
        assertTrue(loading.isLoading)
        assertFalse(loading.isReady)
        assertFalse(loading.isPlaying)

        val playing = PlayerSnapshot(phase = PlaybackPhase.PLAYING, durationMs = 1440_000L)
        assertFalse(playing.isLoading)
        assertTrue(playing.isReady)
        assertTrue(playing.isPlaying)
    }
}

package id.my.milkglass

import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.source.CascadeResult
import id.my.milkglass.source.MediaContainer
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.MkvSourceProvider
import id.my.milkglass.source.RawCandidateStore
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.SourceCircuitBreaker
import id.my.milkglass.source.SourcePriority
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
}

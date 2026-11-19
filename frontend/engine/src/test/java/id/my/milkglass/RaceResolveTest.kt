package id.my.milkglass

import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.StreamResolutionException
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.MkvSourceProvider
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.SourceCircuitBreaker
import id.my.milkglass.source.SourcePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolve kini membalap server (Cloudflare hint) dan cascade lokal. Dua hal yang tidak boleh rusak:
 * kegagalan harus bersih (bukan menggantung/jenis galat salah) dan pembatalan (ganti episode) harus
 * menghentikan semua peserta balapan dengan cepat.
 */
class RaceResolveTest {

    private class EmptyProvider(override val priority: SourcePriority, private val sleepMs: Long) : MkvSourceProvider {
        override fun findEpisodeMkvCandidates(
            animeTitle: String,
            episodeNumber: Int,
            targetQualities: Set<MkvQuality>
        ): List<RawMkvCandidate> {
            Thread.sleep(sleepMs)
            return emptyList()
        }
    }

    private fun newResolver(sleepMs: Long): StreamResolver {
        val clients = IsolatedHttpClients(null)
        return StreamResolver(
            clients = clients,
            cascadeResolver = MkvCascadeResolver(
                clients = clients,
                providers = listOf(
                    EmptyProvider(SourcePriority.OTAKUDESU, sleepMs),
                    EmptyProvider(SourcePriority.SAMEHADAKU, sleepMs)
                )
            )
        )
    }

    @Test
    fun noSourceHasEpisodeFailsCleanlyAsNotFound() {
        SourceCircuitBreaker.resetAll()
        val resolver = newResolver(sleepMs = 50)
        val t0 = System.nanoTime()
        val error = runBlocking {
            runCatching { resolver.resolveAndStartStream(slug = "judul-uji-race-ep-1", title = "Judul Uji Race") }.exceptionOrNull()
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("harus StreamResolutionException, dapat: $error", error is StreamResolutionException)
        assertEquals(FailureReason.NOT_FOUND, (error as StreamResolutionException).reason)
        assertTrue("gagal harus cepat, bukan menunggu timeout (${elapsedMs}ms)", elapsedMs < 5_000L)
    }

    @Test
    fun cancelWhileRacingStopsPromptlyAndNeverTripsCircuitBreaker() {
        SourceCircuitBreaker.resetAll()
        val resolver = newResolver(sleepMs = 8_000)
        val elapsedMs = runBlocking {
            val job = launch(Dispatchers.Default) {
                resolver.resolveAndStartStream(slug = "judul-uji-cancel-ep-2", title = "Judul Uji Cancel")
            }
            delay(1_800) // lewati HEDGE_DELAY cascade supaya kedua provider sudah berjalan
            val t0 = System.nanoTime()
            job.cancelAndJoin()
            (System.nanoTime() - t0) / 1_000_000L
        }
        assertTrue("pembatalan harus berhenti cepat (${elapsedMs}ms)", elapsedMs < 3_000L)
        assertTrue(!SourceCircuitBreaker.isOpen(SourcePriority.OTAKUDESU))
        assertTrue(!SourceCircuitBreaker.isOpen(SourcePriority.SAMEHADAKU))
    }
}

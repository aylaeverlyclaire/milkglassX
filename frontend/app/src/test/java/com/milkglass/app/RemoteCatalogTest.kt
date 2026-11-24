package com.milkglass.app

import com.milkglass.app.core.data.SeedData
import com.milkglass.app.core.remote.CATALOG_STALE_AFTER_SEC
import com.milkglass.app.core.remote.CatalogCache
import com.milkglass.app.core.remote.CatalogHttpException
import com.milkglass.app.core.remote.CatalogRepository
import com.milkglass.app.core.remote.CatalogSource
import com.milkglass.app.core.remote.CatalogTransport
import com.milkglass.app.core.remote.CatalogUnavailable
import com.milkglass.app.core.remote.RemoteAnime
import com.milkglass.app.core.remote.RemoteJson
import com.milkglass.app.core.remote.RemoteMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCatalogTest {

    private val ongoingJson = """{"id":301,"mal_id":401,"title":"Romaji 301","title_alt":"English 301","score":8.1,"type":"TV","status":1,
        "schedule_day":2,"duration":"24 min","studio":"Studio 0","release_season":"SPRING 2022","poster_url":"https://img.test/301.jpg",
        "popularity":996990,"updated_at":1794714396,"episodes_total":0,"next_airing_at":1794887186,"next_airing_ep":10,
        "genres":["Fantasy","Drama","Sci-Fi","Ecchi"]}"""
    private val finishedJson = """{"id":16498,"mal_id":16498,"title":"Shingeki no Kyojin","title_alt":"Attack on Titan","score":8.5,"type":"TV",
        "status":2,"schedule_day":3,"duration":"24 min","studio":"-","release_season":"SPRING 2013","poster_url":"http://insecure.test/a.jpg",
        "popularity":1078955,"updated_at":1794714396,"episodes_total":25,"next_airing_at":0,"next_airing_ep":0,"genres":["Action","Mystery"]}"""

    private fun homeJson(syncedAt: Long? = 1794714396L, popular: String = "[$finishedJson,$ongoingJson]") =
        """{"status":"ok","data":{"ongoing":[$ongoingJson],"popular":$popular,"tamat":[$finishedJson],"extras":[$finishedJson]},
            "meta":{"synced_at":${syncedAt ?: "null"},"full_synced_at":${syncedAt ?: "null"}}}"""

    private fun remote(json: String): RemoteAnime = RemoteJson.decodeFromString(RemoteAnime.serializer(), json)

    @Test fun ongoingWithoutKnownTotal() {
        val a = RemoteMapper.toAnime(remote(ongoingJson))
        assertEquals("English 301", a.title)
        assertEquals("Romaji 301", a.romaji)
        assertEquals(listOf("fantasy", "drama", "sci-fi"), a.genres)
        assertEquals(9, a.releasedEpisodes)
        assertEquals(10, a.totalEpisodes)
        assertEquals(2, a.airWeekday)
        assertEquals("10:46", a.airTime)
        assertEquals(1794887186000L, a.nextAirAt)
        assertEquals(2022, a.year)
        assertEquals(24, a.episodeMinutes)
        assertEquals("Studio 0", a.studio)
        assertEquals(8.1, a.rating, 0.0)
        assertEquals(996990, a.voteCount)
        assertEquals("", a.ageRating)
        assertEquals(listOf(0, 0), a.openingRange)
        assertTrue(a.similarIds.isEmpty())
    }

    @Test fun ongoingWithoutNextAiringEpUsesCuratedEpisodesTotalInsteadOfCollapsingToOne() {
        val r = remote(ongoingJson).copy(
            episodesTotal = 191,
            nextAiringAt = 0,
            nextAiringEp = 0,
            sourceUrls = mapOf("ylnime" to "https://ylnime.com/index.php?series=lingwu-continent")
        )
        val a = RemoteMapper.toAnime(r)
        assertEquals(191, a.releasedEpisodes)
        assertEquals(191, a.totalEpisodes)
    }

    @Test fun finishedWithKnownTotal() {
        val a = RemoteMapper.toAnime(remote(finishedJson))
        assertEquals(25, a.totalEpisodes)
        assertEquals(25, a.releasedEpisodes)
        assertNull(a.airWeekday)
        assertEquals("", a.airTime)
        assertNull(a.nextAirAt)
        assertEquals("", a.studio)
        assertEquals(listOf("action", "mystery"), a.genres)
    }

    @Test fun finishedWithUnknownTotalIsNotOngoing() {
        val a = RemoteMapper.toAnime(remote(finishedJson).copy(episodesTotal = 0))
        assertEquals(0, a.totalEpisodes)
        assertEquals(0, a.releasedEpisodes)
    }

    @Test fun staleScheduleDayWithoutAirTimeNeverLeavesWeekdayWithoutClock() {

        val a = RemoteMapper.toAnime(remote(finishedJson).copy(status = 1, scheduleDay = 3, nextAiringAt = 0, nextAiringEp = 0))
        assertNull(a.airWeekday)
        assertEquals("", a.airTime)

        val booted = SeedData.withNextAirAt(listOf(a), 1794800000000L)
        assertNull(booted[0].nextAirAt)
    }

    @Test fun mappedOngoingWorksWithSeedNextAirAt() {
        val a = RemoteMapper.toAnime(remote(ongoingJson))

        val booted = SeedData.withNextAirAt(listOf(a), 1794800000000L)
        assertEquals(1794887160000L, booted[0].nextAirAt)
    }

    @Test fun weekdayAndClockAreWib() {
        assertEquals(2, RemoteMapper.wibWeekday(1794887186L))
        assertEquals(3, RemoteMapper.wibWeekday(1794960000L))
        assertEquals("10:46", RemoteMapper.wibClock(1794887186L))
        assertEquals("00:00", RemoteMapper.wibClock(1794848400L))
        assertEquals(2, RemoteMapper.wibWeekday(1794848400L))
        assertEquals(1, RemoteMapper.wibWeekday(1794848399L))
        assertEquals("23:59", RemoteMapper.wibClock(1794848399L))
        assertEquals(4, RemoteMapper.wibWeekday(0L))
    }

    @Test fun posterOnlyHttps() {
        assertEquals("https://img.test/301.jpg", RemoteMapper.toEntry(remote(ongoingJson)).posterUrl)
        assertNull(RemoteMapper.toEntry(remote(finishedJson)).posterUrl)
        assertEquals(401, RemoteMapper.toEntry(remote(ongoingJson)).malId)
    }

    @Test fun colorPairIsDeterministicValidAndVaried() {
        val hex = Regex("^#[0-9a-f]{6}$")
        val (f1, t1) = RemoteMapper.colorPair(301)
        assertTrue(hex.matches(f1) && hex.matches(t1))
        assertEquals(f1 to t1, RemoteMapper.colorPair(301))
        assertNotEquals(RemoteMapper.colorPair(1), RemoteMapper.colorPair(2))
        assertTrue((0..2000).all { id -> RemoteMapper.colorPair(id).let { hex.matches(it.first) && hex.matches(it.second) } })
    }

    @Test fun decoderToleratesOlderWorkerAndNulls() {

        val a = remote("""{"id":7,"title":"X","status":null,"popularity":null,"genres":null,"unknown_field":1}""")
        assertEquals(7, a.id)
        assertEquals(2, a.status)
        assertEquals(0, a.popularity)
        assertTrue(a.genres.isEmpty())
        assertEquals(0, a.episodesTotal)
    }

    private class FakeTransport(private val answers: MutableMap<String, () -> String>) : CatalogTransport {
        val requested = mutableListOf<String>()
        override suspend fun get(pathAndQuery: String): String {
            requested += pathAndQuery
            return (answers[pathAndQuery] ?: { throw CatalogHttpException(404) })()
        }
    }

    private class MemCache(var value: String? = null) : CatalogCache {
        var writes = 0
        override fun read(): String? = value
        override fun write(json: String) { value = json; writes++ }
    }

    private fun repo(t: CatalogTransport, c: CatalogCache, nowMs: Long = 1794714396_000L + 3_600_000L) =
        CatalogRepository(t, c, { nowMs })

    @Test fun networkSuccessIsCachedAndFresh() = runBlocking {
        val cache = MemCache()
        val home = repo(FakeTransport(mutableMapOf("/v1/catalog/home" to { homeJson() })), cache).home().getOrThrow()
        assertEquals(CatalogSource.NETWORK, home.source)
        assertEquals(2, home.popular.size)
        assertEquals(1, home.ongoing.size)
        assertEquals(1794714396L, home.syncedAtSec)
        assertFalse(home.stale)
        assertEquals(1, cache.writes)
        assertEquals(homeJson(), cache.value)
    }

    @Test fun networkFailureFallsBackToCache() = runBlocking {
        val cache = MemCache(homeJson())
        val boom = FakeTransport(mutableMapOf("/v1/catalog/home" to { throw java.io.IOException("offline") }))
        val home = repo(boom, cache).home().getOrThrow()
        assertEquals(CatalogSource.CACHE, home.source)
        assertEquals(0, cache.writes)
    }

    @Test fun invalidNetworkBodyDoesNotOverwriteGoodCache() = runBlocking {
        val good = homeJson()
        val cache = MemCache(good)
        for (bad in listOf("<html>cf error</html>", """{"status":"error","message":"Server lagi sibuk."}""", homeJson(popular = "[]"))) {
            val home = repo(FakeTransport(mutableMapOf("/v1/catalog/home" to { bad })), cache).home().getOrThrow()
            assertEquals(CatalogSource.CACHE, home.source)
        }
        assertEquals(good, cache.value)
        assertEquals(0, cache.writes)
    }

    @Test fun noNetworkAndNoCacheIsFailure() = runBlocking {
        val r = repo(FakeTransport(mutableMapOf()), MemCache()).home()
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is CatalogUnavailable)
    }

    @Test fun emptyCatalogFromServerIsNotCached() = runBlocking {
        val cache = MemCache()
        val r = repo(FakeTransport(mutableMapOf("/v1/catalog/home" to { homeJson(popular = "[]") })), cache).home()
        assertTrue(r.isFailure)
        assertEquals(0, cache.writes)
    }

    @Test fun staleness() = runBlocking {
        val synced = 1794714396L
        fun staleAt(offsetSec: Long, meta: Long? = synced) = runBlocking {
            repo(FakeTransport(mutableMapOf("/v1/catalog/home" to { homeJson(meta) })), MemCache(), (synced + offsetSec) * 1000).home().getOrThrow().stale
        }
        assertFalse(staleAt(CATALOG_STALE_AFTER_SEC))
        assertTrue(staleAt(CATALOG_STALE_AFTER_SEC + 1))
        assertTrue(staleAt(0, meta = null))
    }

    @Test fun browseBuildsEncodedQueryAndMaps() = runBlocking {
        val body = """{"status":"ok","data":[$ongoingJson],"meta":{"page":2,"limit":24}}"""
        val path = "/v1/catalog/browse?q=attack+on+titan&genre=Slice+of+Life&status=1&page=2"
        val t = FakeTransport(mutableMapOf(path to { body }))
        val r = repo(t, MemCache()).browse(q = "  attack on titan ", genre = "Slice of Life", status = 1, page = 2).getOrThrow()
        assertEquals(listOf(path), t.requested)
        assertEquals(301, r.single().anime.id)
    }

    @Test fun browseWithoutFiltersOnlySendsPage() = runBlocking {
        val t = FakeTransport(mutableMapOf("/v1/catalog/browse?page=1" to { """{"status":"ok","data":[]}""" }))
        assertTrue(repo(t, MemCache()).browse().getOrThrow().isEmpty())
    }

    @Test fun detailMapsSynopsisAndHandlesNotFound() = runBlocking {
        val ok = """{"status":"ok","data":{"id":301,"title":"Romaji 301","synopsis":"Sinopsis","genres":["Drama"],"genres_json":"[\"Drama\"]","episodes":[]}}"""
        val t = FakeTransport(mutableMapOf("/v1/catalog/anime/301" to { ok }, "/v1/catalog/anime/9" to { throw CatalogHttpException(404) }))
        val r = repo(t, MemCache())
        assertEquals("Sinopsis", r.detail(301).getOrThrow().anime.synopsis)
        assertTrue(r.detail(9).exceptionOrNull() is CatalogUnavailable)
    }

    @Test fun excludedFranchisesAndOver500EpisodesAreFilteredOut() {
        val conan = remote(ongoingJson).copy(id = 1533192982, title = "Detective Conan", episodesTotal = 1200)
        val meitantei = remote(ongoingJson).copy(id = 1047429090, title = "Meitantei Conan: Hannin no Hanzawa-san", episodesTotal = 12)
        val naruto = remote(ongoingJson).copy(id = 1213776837, title = "Naruto Bocil", episodesTotal = 220)
        val shippuuden = remote(ongoingJson).copy(id = 1496322565, title = "Naruto: Shippuuden", episodesTotal = 500)
        val boruto = remote(ongoingJson).copy(id = 1571382747, title = "Boruto: Naruto Next Generations", episodesTotal = 293)
        val onePiece = remote(ongoingJson).copy(id = 1555631549, title = "One Piece", episodesTotal = 1174)
        val martialMaster = remote(ongoingJson).copy(id = 1389695603, title = "Martial Master", episodesTotal = 720)
        val frieren = remote(ongoingJson).copy(id = 154587, title = "Sousou no Frieren", episodesTotal = 28)

        val entries = RemoteMapper.toEntries(listOf(conan, meitantei, naruto, shippuuden, boruto, onePiece, martialMaster, frieren))
        assertEquals(listOf(154587), entries.map { it.anime.id })
    }
}

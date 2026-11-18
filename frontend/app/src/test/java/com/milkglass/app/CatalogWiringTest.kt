package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.data.AppData
import com.milkglass.app.core.poster.PosterStore
import com.milkglass.app.core.remote.CatalogCache
import com.milkglass.app.core.remote.CatalogHttpException
import com.milkglass.app.core.remote.CatalogRepository
import com.milkglass.app.core.remote.CatalogTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogWiringTest {

    private class FakeTransport(val answers: MutableMap<String, String> = mutableMapOf(), var down: Boolean = false) : CatalogTransport {
        val calls = mutableListOf<String>()
        override suspend fun get(pathAndQuery: String): String {
            calls += pathAndQuery
            if (down) throw CatalogHttpException(503)
            return answers[pathAndQuery] ?: throw CatalogHttpException(404)
        }
    }

    private object NoCache : CatalogCache {
        override fun read(): String? = null
        override fun write(json: String) {}
    }

    private fun row(id: Int, pop: Int, genres: String = "\"Action\"") =
        """{"id":$id,"mal_id":$id,"title":"Judul $id","title_alt":"Title $id","score":7.5,"type":"TV","status":2,"schedule_day":0,
            "duration":"24 min","studio":"S","release_season":"WINTER 2020","poster_url":"https://img.test/$id.jpg","popularity":$pop,
            "updated_at":1,"episodes_total":12,"next_airing_at":0,"next_airing_ep":0,"genres":[$genres]}"""

    private fun page(vararg rows: String) = """{"status":"ok","data":[${rows.joinToString(",")}],"meta":{"page":1,"limit":24}}"""

    
    private fun fullPage(from: Int) = page(*(from until from + 24).map { row(it, pop = it * 10) }.toTypedArray())

    private fun noSeed(): AppData = TestSupport.data.let { AppData(it.locale, emptyList(), it.users, it.seedConversationsRaw, it.features) }

    private fun ctl(t: CatalogTransport, data: AppData = noSeed()) =
        AppController(data, PosterStore(emptyMap()), null, catalogRepo = CatalogRepository(t, NoCache))

    @Test fun appStartsWithoutSeedOrFakeUserData() {
        val c = ctl(FakeTransport())
        assertTrue(c.catalog.isEmpty())
        assertTrue(c.persisted.history.isEmpty())
        assertTrue(c.persisted.watchlist.isEmpty())
        assertTrue(c.persisted.progress.isEmpty())
        assertTrue(c.persisted.recentSearches.isEmpty())
        assertTrue(c.searchSuggestions().isEmpty())
    }

    @Test fun loadsPagesUntilShortPage_andFeedsTrendingByVoteCount() = runBlocking {
        val t = FakeTransport()
        t.answers["/v1/catalog/browse?page=1"] = fullPage(1)
        t.answers["/v1/catalog/browse?page=2"] = page(row(500, pop = 9_999_999), row(501, pop = 5))
        val c = ctl(t)
        c.loadRemoteCatalog()

        assertEquals(26, c.catalog.size)
        assertEquals(listOf("/v1/catalog/browse?page=1", "/v1/catalog/browse?page=2"), t.calls) 
        assertFalse(c.catalogLoading)
        assertFalse(c.catalogFailed)

        val trending = c.trendingList()
        assertEquals(500, trending.first().id)                                   
        assertEquals(trending.map { it.voteCount }.sortedDescending(), trending.map { it.voteCount })
        assertEquals(501, trending.last().id)                                    

        c.loadRemoteCatalog()                                                    
        assertEquals(2, t.calls.size)
    }

    @Test fun lookupIsById_notByPosition_andPostersAreBlankForRemote() = runBlocking {
        val t = FakeTransport()
        t.answers["/v1/catalog/browse?page=1"] = page(row(7, pop = 1), row(16498, pop = 2))
        val c = ctl(t)
        c.loadRemoteCatalog()
        assertEquals(7, c.animeOf(7)?.id)                  
        assertEquals(16498, c.animeOf(16498)?.id)
        assertNull(c.animeOf(3))
        assertTrue(c.posterSpec(c.animeOf(7)!!, false).ops.isEmpty())   
        assertTrue(c.posterSpec(c.animeOf(7)!!, true).ops.isEmpty())
        assertEquals("https://img.test/7.jpg", c.posterSpec(c.animeOf(7)!!, false).url)   
        assertEquals("https://img.test/16498.jpg", c.posterSpec(c.animeOf(16498)!!, true).url)
    }

    @Test fun failureKeepsWhatLoaded_marksFailed_andRetryRecovers() = runBlocking {
        val t = FakeTransport(down = true)
        val c = ctl(t)
        c.loadRemoteCatalog()
        assertTrue(c.catalogFailed)
        assertFalse(c.catalogLoading)
        assertTrue(c.catalog.isEmpty())

        t.down = false
        t.answers["/v1/catalog/browse?page=1"] = page(row(1, pop = 1))
        c.loadRemoteCatalog()
        assertFalse(c.catalogFailed)
        assertEquals(1, c.catalog.size)
    }

    @Test fun searchFieldCallsBrowseWithQuery_andMergesResults() = runBlocking {
        val t = FakeTransport()
        t.answers["/v1/catalog/browse?q=Judul+999&page=1"] = page(row(999, pop = 3))
        val c = ctl(t)
        c.searchRemote("a")            
        c.searchRemote("   ")
        assertTrue(t.calls.isEmpty())

        c.searchRemote(" Judul 999 ")
        assertEquals(listOf("/v1/catalog/browse?q=Judul+999&page=1"), t.calls)
        assertNotNull(c.animeOf(999))   

        c.searchQuery = "judul 999"
        assertEquals(listOf(999), c.searchCatalog().map { it.id })
    }

    @Test fun similarIsComputedFromSharedGenres_thenPopularity() = runBlocking {
        val t = FakeTransport()
        t.answers["/v1/catalog/browse?page=1"] = page(
            row(1, pop = 10, genres = "\"Action\",\"Drama\""),
            row(2, pop = 50, genres = "\"Action\""),                 
            row(3, pop = 20, genres = "\"Action\",\"Drama\""),     
            row(4, pop = 99, genres = "\"Comedy\""),                 
        )
        val c = ctl(t)
        c.loadRemoteCatalog()
        assertEquals(listOf(3, 2), c.similarOf(c.animeOf(1)!!).map { it.id })
    }

    @Test fun episodeTitlesAreNeverInvented() {
        val c = ctl(FakeTransport())
        val a = TestSupport.data.catalogRaw.first()
        assertEquals("Episode 3", c.time.episodeTitle(a, 3))
        assertEquals("Episode 3", c.time.episodeTitle(a.copy(id = 999), 3))
    }

    @Test fun seededControllerStillWorksForExistingTests() {
        val c = AppController(TestSupport.data, PosterStore(emptyMap()), null, seedState = TestSeed.state)
        assertEquals(12, c.catalog.size)
        assertEquals(c.catalog[5], c.animeOf(5))
        assertTrue(c.persisted.history.isNotEmpty())
    }
}

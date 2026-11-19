package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.data.AppData
import com.milkglass.app.core.poster.PosterStore
import com.milkglass.app.core.remote.BACKOFF_BASE_MS
import com.milkglass.app.core.remote.BACKOFF_MAX_MS
import com.milkglass.app.core.remote.CatalogCache
import com.milkglass.app.core.remote.CatalogHttpException
import com.milkglass.app.core.remote.CatalogRepository
import com.milkglass.app.core.remote.CatalogTransport
import com.milkglass.app.core.remote.RECHECK_MAX_SEC
import com.milkglass.app.core.remote.RECHECK_MIN_SEC
import com.milkglass.app.core.remote.RefreshOutcome
import com.milkglass.app.core.remote.SnapshotState
import com.milkglass.app.core.remote.SnapshotStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSnapshotTest {

    private class FakeTransport : CatalogTransport {
        val answers = mutableMapOf<String, () -> String>()
        val calls = mutableListOf<String>()
        override suspend fun get(pathAndQuery: String): String {
            calls += pathAndQuery
            return (answers[pathAndQuery] ?: { throw CatalogHttpException(404) })()
        }
    }

    private class MemSnapshots : SnapshotStore {
        var body: String? = null
        var state: SnapshotState? = null
        var bodyWrites = 0
        override fun readBody() = body
        override fun readState() = state
        override fun writeBody(json: String) { body = json; bodyWrites++ }
        override fun writeState(state: SnapshotState) { this.state = state }
    }

    private object NoCache : CatalogCache {
        override fun read(): String? = null
        override fun write(json: String) {}
    }

    private fun row(id: Int, pop: Int) =
        """{"id":$id,"mal_id":$id,"title":"Judul $id","title_alt":"Title $id","score":7.5,"type":"TV","status":2,"schedule_day":0,
            "duration":"24 min","studio":"S","release_season":"WINTER 2020","poster_url":"https://img.test/$id.jpg","popularity":$pop,
            "updated_at":1,"episodes_total":12,"next_airing_at":0,"next_airing_ep":0,"genres":["Action"]}"""

    private fun statusJson(version: String?, recheck: Long? = 900): String {
        val fields = mutableListOf("\"total\":3")
        if (version != null) fields += "\"version\":\"$version\""
        if (recheck != null) fields += "\"recheck_after_sec\":$recheck"
        return "{\"status\":\"ok\",\"data\":{" + fields.joinToString(",") + "}}"
    }

    private fun snapshotJson(version: String, vararg ids: Int, count: Int = ids.size) =
        """{"status":"ok","data":[${ids.joinToString(",") { row(it, it * 10) }}],"meta":{"version":"$version","count":$count}}"""

    private val t0 = 1_800_000_000_000L
    private var now = t0

    private fun setup(): Triple<CatalogRepository, FakeTransport, MemSnapshots> {
        val t = FakeTransport()
        val s = MemSnapshots()
        return Triple(CatalogRepository(t, NoCache, { now }, s), t, s)
    }

    private fun FakeTransport.server(version: String, vararg ids: Int, recheck: Long? = 900) {
        answers["/v1/catalog/status"] = { statusJson(version, recheck) }
        answers["/v1/catalog/snapshot?v=$version"] = { snapshotJson(version, *ids) }
    }

    @Test fun coldStartFetchesStatusThenSnapshotAndPersists() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        val r = repo.refreshSnapshot()
        assertTrue(r is RefreshOutcome.Updated)
        assertEquals(3, (r as RefreshOutcome.Updated).entries.size)
        assertTrue(r.complete)
        assertEquals(listOf("/v1/catalog/status", "/v1/catalog/snapshot?v=100.3"), t.calls)
        assertEquals("100.3", s.state?.version)
        assertEquals(t0 + 900_000, s.state?.validUntilMs)

        val cached = CatalogRepository(FakeTransport(), NoCache, { now }, s).cachedSnapshot()
        assertEquals(3, cached?.entries?.size)
        assertTrue(cached?.complete == true)
    }

    @Test fun whileValidThereIsNoNetworkAtAll() = runBlocking {
        now = t0
        val (repo, t, _) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        t.calls.clear()
        now = t0 + 899_000
        assertEquals(RefreshOutcome.Skipped, repo.refreshSnapshot())
        assertTrue(t.calls.isEmpty())
    }

    @Test fun expiredButSameVersionOnlyAsksStatusAndExtends() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        t.calls.clear()
        now = t0 + 901_000
        t.answers["/v1/catalog/status"] = { statusJson("100.3", 3600) }
        assertEquals(RefreshOutcome.Unchanged, repo.refreshSnapshot())
        assertEquals(listOf("/v1/catalog/status"), t.calls)
        assertEquals(1, s.bodyWrites)
        assertEquals(now + 3_600_000, s.state?.validUntilMs)
    }

    @Test fun newVersionDownloadsSnapshotByVersionKey() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        t.calls.clear()
        now = t0 + 1_000_000
        t.server("200.4", 1, 2, 3, 4)
        val r = repo.refreshSnapshot() as RefreshOutcome.Updated
        assertEquals(4, r.entries.size)
        assertEquals(listOf("/v1/catalog/status", "/v1/catalog/snapshot?v=200.4"), t.calls)
        assertEquals("200.4", s.state?.version)
        assertEquals(2, s.bodyWrites)
    }

    @Test fun serverHintIsClamped() = runBlocking {
        for ((hint, expectedSec) in listOf(5L to RECHECK_MIN_SEC, 10_000_000L to RECHECK_MAX_SEC)) {
            now = t0
            val (repo, t, s) = setup()
            t.server("1.1", 1, recheck = hint)
            repo.refreshSnapshot()
            assertEquals(t0 + expectedSec * 1000, s.state?.validUntilMs)
        }
    }

    @Test fun offlineKeepsCacheAndBacksOffExponentially() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        t.answers.clear()
        t.answers["/v1/catalog/status"] = { throw java.io.IOException("offline") }
        now = t0 + 1_000_000
        assertEquals(RefreshOutcome.Failed, repo.refreshSnapshot())
        assertEquals(now + BACKOFF_BASE_MS, s.state?.validUntilMs)
        assertEquals(1, s.state?.failures)
        assertNotNull(repo.cachedSnapshot())

        t.calls.clear()
        now += BACKOFF_BASE_MS - 1
        assertEquals(RefreshOutcome.Skipped, repo.refreshSnapshot())
        assertTrue(t.calls.isEmpty())

        now += 1
        assertEquals(RefreshOutcome.Failed, repo.refreshSnapshot())
        assertEquals(now + 2 * BACKOFF_BASE_MS, s.state?.validUntilMs)
        repeat(8) { now = s.state!!.validUntilMs; repo.refreshSnapshot() }
        assertEquals(now + BACKOFF_MAX_MS, s.state?.validUntilMs)
    }

    @Test fun forceIgnoresValidityAndBackoff() = runBlocking {
        now = t0
        val (repo, t, _) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        t.calls.clear()
        assertEquals(RefreshOutcome.Unchanged, repo.refreshSnapshot(force = true))
        assertEquals(1, t.calls.size)
    }

    @Test fun clockGoingBackwardsDoesNotFreezeUpdates() = runBlocking {
        now = t0
        val (repo, t, _) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        t.calls.clear()
        now = t0 - 86_400_000L
        assertEquals(RefreshOutcome.Unchanged, repo.refreshSnapshot())
        assertEquals(1, t.calls.size)
    }

    @Test fun oldWorkerMeansUnsupported() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.answers["/v1/catalog/status"] = { statusJson(version = null, recheck = null) }
        assertEquals(RefreshOutcome.Unsupported, repo.refreshSnapshot())
        assertNull(s.state)

        val (repo2, t2, _) = setup()
        assertEquals(RefreshOutcome.Unsupported, repo2.refreshSnapshot())
        assertEquals(1, t2.calls.size)

        val (repo3, t3, _) = setup()
        t3.answers["/v1/catalog/status"] = { statusJson("9.9") }
        assertEquals(RefreshOutcome.Unsupported, repo3.refreshSnapshot())
    }

    @Test fun badSnapshotNeverOverwritesGoodOne() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        val good = s.body
        now = t0 + 1_000_000
        for (bad in listOf("<html>cf error</html>", """{"status":"error","message":"x"}""", snapshotJson("200.0"))) {
            t.answers["/v1/catalog/status"] = { statusJson("200.0") }
            t.answers["/v1/catalog/snapshot?v=200.0"] = { bad }
            assertEquals(RefreshOutcome.Failed, repo.refreshSnapshot(force = true))
        }
        assertEquals(good, s.body)
        assertEquals("100.3", s.state?.version)
    }

    @Test fun partialSnapshotIsMarkedIncomplete() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.answers["/v1/catalog/status"] = { statusJson("5.9") }
        t.answers["/v1/catalog/snapshot?v=5.9"] = { snapshotJson("5.9", 1, 2, count = 9) }
        val r = repo.refreshSnapshot() as RefreshOutcome.Updated
        assertFalse(r.complete)
        assertFalse(s.state!!.complete)
    }

    @Test fun incompatibleSchemaOnDiskIsDiscarded() {
        val s = MemSnapshots()
        s.body = snapshotJson("1.1", 1)
        s.state = SnapshotState(schema = 0, version = "1.1", fetchedAtMs = 0, validUntilMs = Long.MAX_VALUE)
        assertNull(CatalogRepository(FakeTransport(), NoCache, { now }, s).cachedSnapshot())
    }

    private fun noSeed(): AppData = TestSupport.data.let { AppData(it.locale, emptyList(), it.users, it.seedConversationsRaw, it.features) }

    @Test fun cachedCatalogIsVisibleBeforeAnyNetwork_andSearchSkipsServerWhenComplete() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        val cached = repo.cachedSnapshot()
        val t2 = FakeTransport()
        val repo2 = CatalogRepository(t2, NoCache, { now }, s)
        val c = AppController(noSeed(), PosterStore(emptyMap()), null, catalogRepo = repo2, initialCatalog = cached)
        assertEquals(3, c.catalog.size)
        assertTrue(t2.calls.isEmpty())

        c.searchRemote("judul")
        assertTrue(t2.calls.isEmpty())

        c.loadRemoteCatalog()
        assertTrue(t2.calls.isEmpty())
        assertFalse(c.catalogLoading)
        assertFalse(c.catalogFailed)
    }

    @Test fun offlineWithCacheIsNotShownAsFailure() = runBlocking {
        now = t0
        val (repo, t, s) = setup()
        t.server("100.3", 1, 2, 3)
        repo.refreshSnapshot()
        val t2 = FakeTransport().also { it.answers["/v1/catalog/status"] = { throw java.io.IOException("offline") } }
        now = t0 + 10_000_000
        val c = AppController(
            noSeed(), PosterStore(emptyMap()), null,
            catalogRepo = CatalogRepository(t2, NoCache, { now }, s), initialCatalog = repo.cachedSnapshot(),
        )
        c.loadRemoteCatalog()
        assertEquals(3, c.catalog.size)
        assertFalse(c.catalogFailed)
    }

    @Test fun offlineWithoutCacheIsFailureThenRetryRecovers() = runBlocking {
        now = t0
        val (repo, t, _) = setup()
        t.answers["/v1/catalog/status"] = { throw java.io.IOException("offline") }
        val c = AppController(noSeed(), PosterStore(emptyMap()), null, catalogRepo = repo)
        c.loadRemoteCatalog()
        assertTrue(c.catalogFailed)
        t.server("100.3", 1, 2)
        c.retryCatalogIfFailed()
        assertFalse(c.catalogFailed)
        assertEquals(2, c.catalog.size)
    }

    @Test fun bootWarmsFirstScreenPostersAndFinishes() = runBlocking {
        now = t0
        val (repo, t, _) = setup()
        t.server("100.3", 1, 2, 3)
        val c = AppController(noSeed(), PosterStore(emptyMap()), null, catalogRepo = repo)
        var warmed = emptyList<String>()
        coroutineScope {
            launch { c.loadRemoteCatalog() }
            c.boot { warmed = it }
        }
        assertFalse(c.isBooting)
        assertEquals(setOf("https://img.test/1.jpg", "https://img.test/2.jpg", "https://img.test/3.jpg"), warmed.toSet())
    }
}

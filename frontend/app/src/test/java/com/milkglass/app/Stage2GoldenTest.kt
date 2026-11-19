package com.milkglass.app

import com.milkglass.app.core.app.Access
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.poster.PosterStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class Stage2GoldenTest {
    private val g = Json.parseToJsonElement(TestSupport.resource("golden/stage2_golden.json")).jsonObject
    private val t0 = g.getValue("now").jsonPrimitive.long
    private val ctl = AppController(
        TestSupport.data,
        PosterStore.load { TestSupport.read("poster_ops.json") },
        store = null, bootMs = t0, clock = { t0 }, zone = TimeZone.getTimeZone("Asia/Jakarta"),
        seedState = TestSeed.state,
    )

    private fun jsTruthy(e: kotlinx.serialization.json.JsonElement?): Boolean {
        if (e == null || e is JsonNull) return false
        val c = e.jsonPrimitive.content
        return c == "true" || (c.toDoubleOrNull()?.let { it != 0.0 } ?: false)
    }

    private fun ids(key: String) = g.getValue(key).jsonArray.map { it.jsonPrimitive.int }

    @Test fun releaseState() {
        g.getValue("release").jsonArray.forEach { e ->
            val o = e.jsonObject
            val a = ctl.catalog[o.getValue("id").jsonPrimitive.int]
            assertEquals("released ${a.id}", o.getValue("released").jsonPrimitive.int, ctl.time.releasedEpisodeCount(a))
            assertEquals("ongoing ${a.id}", o.getValue("ongoing").jsonPrimitive.boolean, ctl.time.isOngoing(a))
            val next = o["next"]
            assertEquals("next ${a.id}", if (next == null || next is JsonNull) null else next.jsonPrimitive.long, ctl.time.nextReleaseAt(a))
        }
    }

    @Test fun continueInfoMatches() {
        g.getValue("continue").jsonArray.forEach { e ->
            val o = e.jsonObject
            val a = ctl.catalog[o.getValue("id").jsonPrimitive.int]
            val info = o["info"]
            val actual = ctl.continueInfo(a)
            if (info == null || info is JsonNull) { assertNull("info ${a.id}", actual); return@forEach }
            val i = info as JsonObject
            val ep = i["episode"]
            assertEquals("episode ${a.id}", if (ep == null || ep is JsonNull) null else ep.jsonPrimitive.int, actual!!.episode)
            assertEquals("position ${a.id}", i["position"]?.jsonPrimitive?.double ?: 0.0, actual.position, 1e-9)
            assertEquals("waiting ${a.id}", jsTruthy(i["waiting"]), actual.waiting)
            assertEquals("completed ${a.id}", jsTruthy(i["completed"]), actual.completed)
        }
    }

    @Test fun listsMatch() {
        assertEquals(ids("contList"), ctl.continueWatchingList().map { it.id })
        assertEquals(ids("trending"), ctl.trendingList().map { it.id })
        assertEquals(ids("recommended"), ctl.recommendedList().map { it.id })
        val aff = g.getValue("affinity").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(aff, ctl.genreAffinity())
    }

    @Test fun episodeAccessMatches() {
        g.getValue("access").jsonArray.forEach { e ->
            val o = e.jsonObject
            val a = ctl.catalog[o.getValue("id").jsonPrimitive.int]
            val released = ctl.time.releasedEpisodeCount(a)
            val eps = listOf(1, 5, 10, released, released + 1, a.totalEpisodes)
            val exp = o.getValue("acc").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("access ${a.id}", exp, eps.map { ctl.episodeAccess(a, it).name.lowercase() })
        }
    }

    @Test fun scheduleMatches() {
        g.getValue("schedule").jsonArray.forEachIndexed { k, day ->
            val exp = day.jsonArray.map { Triple(it.jsonObject.getValue("id").jsonPrimitive.int, it.jsonObject.getValue("ts").jsonPrimitive.long, it.jsonObject.getValue("ep").jsonPrimitive.int) }
            assertEquals("hari $k", exp, ctl.scheduleForDay(k).map { Triple(it.a.id, it.ts, it.ep) })
        }
    }

    @Test fun searchMatches() {
        g.getValue("search").jsonObject.forEach { (key, v) ->
            val q = key.substringBeforeLast("|")
            val genre = key.substringAfterLast("|")
            ctl.searchQuery = q; ctl.submittedQuery = q; ctl.searchGenre = genre
            assertEquals("cari '$key'", v.jsonArray.map { it.jsonPrimitive.int }, ctl.searchCatalog().map { it.id })
        }
    }

    @Test fun watchlistToggleAndLockedToast() {
        val a = ctl.catalog[3]
        val before = ctl.isInWatchlist(a)
        ctl.toggleWatchlist(a.id)
        assertEquals(!before, ctl.isInWatchlist(a))
        ctl.toggleWatchlist(a.id)
        assertEquals(before, ctl.isInWatchlist(a))
        val ongoing = ctl.catalog.first { ctl.time.isOngoing(it) }
        val ep = ctl.time.releasedEpisodeCount(ongoing) + 2
        if (ep <= ongoing.totalEpisodes) {
            assertEquals(Access.Lock, ctl.episodeAccess(ongoing, ep))
            ctl.openEpisode(ongoing, ep)
            assertTrue(ctl.toast != null)
        }
    }
}

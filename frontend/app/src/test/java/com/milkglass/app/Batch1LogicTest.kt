package com.milkglass.app

import androidx.compose.ui.graphics.Color
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.GENRES
import com.milkglass.app.core.app.smartResume
import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.data.GENRE_VIBES
import com.milkglass.app.core.data.VIBES
import com.milkglass.app.core.design.accentFor
import com.milkglass.app.core.design.contrastRatio
import com.milkglass.app.core.poster.PosterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class Batch1LogicTest {
    private val t0 = 1_700_000_000_000L
    private fun ctl() = AppController(
        TestSupport.data, PosterStore.load { TestSupport.read("poster_ops.json") },
        store = null, bootMs = t0, clock = { t0 }, zone = TimeZone.getTimeZone("Asia/Jakarta"),
        seedState = TestSeed.state,
    )

    @Test fun smartResumeRewindsMoreAfterLongerGap() {
        assertEquals(5.0, smartResume(5.0, t0, t0), 0.0)
        assertEquals(97.0, smartResume(100.0, t0 - 3_600_000L, t0), 0.0)
        assertEquals(92.0, smartResume(100.0, t0 - 2 * DAY_MS, t0), 0.0)
        assertEquals(85.0, smartResume(100.0, t0 - 10 * DAY_MS, t0), 0.0)
        assertEquals(0.0, smartResume(12.0, t0 - 10 * DAY_MS, t0), 0.0)
    }

    @Test fun undoBringsBackDeletedWatchlistItems() {
        val c = ctl()
        val before = c.persisted.watchlist
        assertTrue(before.isNotEmpty())
        c.selectCollectionTab("watchlist")
        c.toggleSelectAll("watchlist")
        c.confirmDeleteSelection()
        assertTrue(c.persisted.watchlist.isEmpty())
        c.runToastAction(c.toast!!.id)
        assertEquals(before, c.persisted.watchlist)
        assertEquals(null, c.toast)
    }

    @Test fun undoBringsBackClearedHistory() {
        val c = ctl()
        val before = c.persisted
        c.confirmClearHistory()
        assertEquals(0, c.persisted.history.size)
        c.runToastAction(c.toast!!.id)
        assertEquals(before.history, c.persisted.history)
        assertEquals(before.progress, c.persisted.progress)
        assertEquals(before.resumePositions, c.persisted.resumePositions)
        assertEquals(before.lastWatched, c.persisted.lastWatched)
    }

    @Test fun togglingWatchlistOffCanBeUndoneToTheSamePlace() {
        val c = ctl()
        val before = c.persisted.watchlist
        val first = before.first()
        c.toggleWatchlist(first)
        assertFalse(c.persisted.watchlist.contains(first))
        c.runToastAction(c.toast!!.id)
        assertEquals(before, c.persisted.watchlist)
    }

    @Test fun staleToastActionIsIgnored() {
        val c = ctl()
        val before = c.persisted.watchlist
        c.toggleWatchlist(before.first())
        val oldId = c.toast!!.id
        c.showToast("lain")
        c.runToastAction(oldId)
        assertFalse(c.persisted.watchlist.contains(before.first()))
    }

    @Test fun watchingTabListsStartedButUnfinishedTitles() {
        val c = ctl()
        val watching = c.continueWatchingList()
        assertTrue(watching.none { c.isCompleted(it) })
        if (watching.isEmpty()) return
        c.selectCollectionTab("watching")
        c.toggleSelectAll("watching")
        assertEquals(watching.map { "watching|${it.id}" }, c.selection?.keys)
        c.confirmDeleteSelection()
        assertTrue(c.continueWatchingList().isEmpty())
        assertNotNull(c.toast?.onAction)
    }

    @Test fun everyGenreMapsToAtLeastOneKnownVibe() {
        GENRES.forEach { assertTrue("genre $it", GENRE_VIBES[it].orEmpty().isNotEmpty()) }
        assertTrue(GENRE_VIBES.values.all { v -> VIBES.containsAll(v) })
        VIBES.forEach { assertTrue("vibe.$it", TestSupport.data.locale.messages.containsKey("vibe.$it")) }
        assertTrue(TestSupport.data.locale.messages.containsKey("collectionTab.watching"))
    }

    @Test fun vibeFilterNarrowsSearch() {
        val c = ctl()
        c.searchVibe = "tense"
        val r = c.searchCatalog()
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { a -> a.genres.any { "tense" in GENRE_VIBES[it].orEmpty() } })
        c.resetSearch()
        assertEquals("all", c.searchVibe)
    }

    @Test fun accentKeepsReadableContrastOnLightDarkAndBlack() {
        val backgrounds = listOf(Color(0xFFFDF8FF), Color(0xFF131118), Color.Black)
        TestSupport.data.catalogRaw.forEach { a ->
            backgrounds.forEach { bg ->
                assertTrue("${a.title} di atas $bg", contrastRatio(accentFor(a, bg), bg) >= 2.95)
            }
        }
    }
}

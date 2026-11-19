package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.poster.PosterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class CollectionLogicTest {
    private val t0 = 1_700_000_000_000L
    private fun ctl() = AppController(
        TestSupport.data, PosterStore.load { TestSupport.read("poster_ops.json") },
        store = null, bootMs = t0, clock = { t0 }, zone = TimeZone.getTimeZone("Asia/Jakarta"),
        seedState = TestSeed.state,
    )

    @Test fun longPressWatchlistStartsSelection() {
        val c = ctl()
        c.selectCollectionTab("watchlist")
        c.toggleSelection("watchlist|2", true, "collection")
        assertEquals(listOf("watchlist|2"), c.selection?.keys)
        c.toggleSelection("watchlist|6", true, "collection")
        assertEquals(2, c.selection?.keys?.size)
        c.toggleSelection("watchlist|2", false, "collection")
        assertEquals(listOf("watchlist|6"), c.selection?.keys)
        c.toggleSelection("watchlist|6", false, "collection")
        assertNull(c.selection)
    }

    @Test fun longPressHistoryRowIsClearedImmediately_likeHtml() {
        val c = ctl()
        c.selectCollectionTab("history")
        c.toggleSelection("hist|0.24", true, "collection")
        assertNull(c.selection)
    }

    @Test fun selectAllUsesDifferentKeysAndTogglesOff() {
        val c = ctl()
        c.selectCollectionTab("watchlist")
        c.toggleSelectAll("watchlist")
        assertEquals(listOf("list|2", "list|6"), c.selection?.keys)
        c.toggleSelectAll("watchlist")
        assertNull(c.selection)
    }

    @Test fun deleteSelectedWatchlistItems() {
        val c = ctl()
        c.selectCollectionTab("watchlist")
        c.toggleSelectAll("watchlist")
        c.confirmDeleteSelection()
        assertEquals(emptyList<Int>(), c.persisted.watchlist)
        assertNull(c.selection)
        assertNull(c.overlay)
        assertNotNull(c.toast?.onAction)
    }

    @Test fun selectionClearedWhenLeavingCollectionTab() {
        val c = ctl()
        c.selectCollectionTab("watchlist")
        c.toggleSelection("watchlist|2", true, "collection")
        c.validateSelection("home")
        assertNull(c.selection)
    }

    @Test fun clearHistoryWipesAllProgress() {
        val c = ctl()
        c.clearHistory()
        assertEquals(0, c.persisted.history.size)
        assertEquals(0, c.persisted.progress.size)
        assertEquals(0, c.persisted.lastWatched.size)
        assertEquals(0, c.persisted.resumePositions.size)
    }

    @Test fun deleteCompletedRemovesItsProgressAndHistory() {
        val c = ctl()
        val done = c.catalog.first { c.isCompleted(it) }
        c.selectCollectionTab("completed")
        c.toggleSelection("completed|${done.id}", true, "collection")
        c.deleteSelection()
        assertNull(c.persisted.progress[done.id.toString()])
        assertEquals(0, c.persisted.history.count { it.id == done.id })
    }
}

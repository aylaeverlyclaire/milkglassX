package com.milkglass.app

import com.milkglass.app.core.data.SeedData
import com.milkglass.app.core.data.initialState
import com.milkglass.app.core.model.Subscription
import com.milkglass.app.core.storage.PersistedState
import com.milkglass.app.core.storage.StateCodec
import com.milkglass.app.core.storage.StateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StateCodecTest {
    private val now = 1_700_000_000_000L
    private val conv = SeedData.conversations(TestSupport.data.seedConversationsRaw, now)
    private val initial = SeedData.initialState(TestSupport.translator, conv, now)

    @Test fun roundTrip() {
        assertEquals(initial, StateCodec.decode(StateCodec.encode(initial), initial, conv))
    }

    @Test fun initialStateMatchesHtmlSeed() {
        assertEquals(listOf(2, 6), initial.watchlist)
        assertEquals(listOf("dito", "nana"), initial.friendIds)
        assertEquals(listOf("Kaelan", "Mecha"), initial.recentSearches)
        assertEquals(23 + 1, initial.progress.getValue("0").size)
        assertEquals(860.0, initial.progress.getValue("0").getValue("24"), 0.0)
        assertEquals(6, initial.history.size)
        assertEquals(now - 6 * 60_000L, initial.lastWatched.getValue("0"))
    }

    @Test fun wrongTypedKeyIsIgnoredButOthersLoad() {
        val txt = """{"watchlist":"bukan-array","collectionTab":"history","nextGroupId":7}"""
        val s = StateCodec.decode(txt, initial, conv)
        assertEquals(initial.watchlist, s.watchlist)
        assertEquals("history", s.collectionTab)
        assertEquals(7, s.nextGroupId)
    }

    @Test fun postLoadRules() {
        val txt = """{"subscription":{"active":0,"plan":"x"},"profilePhoto":"http://x/y.png"}"""
        val s = StateCodec.decode(txt, initial, conv)
        assertNull(s.subscription)
        assertNull(s.profilePhoto)
        val ok = StateCodec.decode("""{"profilePhoto":"data:image/png;base64,AA=="}""", initial, conv)
        assertEquals("data:image/png;base64,AA==", ok.profilePhoto)
        val active = StateCodec.decode(StateCodec.encode(initial.copy(subscription = Subscription(active = 1, plan = "m"))), initial, conv)
        assertEquals(1, active.subscription?.active)
    }

    @Test fun onlyCompletedDownloadsPersist() {
        val s = StateCodec.decode(StateCodec.encode(initial.copy(downloads = mapOf("0:1" to 100, "0:2" to 40))), initial, conv)
        assertEquals(mapOf("0:1" to 100), s.downloads)
    }

    @Test fun garbageFallsBackToDefaults() {
        assertEquals(initial, StateCodec.decode("{{{ not json", initial, conv))
        assertEquals(initial, StateCodec.decode(null, initial, conv))
    }

    @Test fun fileStoreAtomicRoundTrip() {
        val dir = File.createTempFile("milk", "dir").also { it.delete(); it.mkdirs() }
        val store = StateStore(File(dir, StateStore.FILE_NAME))
        assertTrue(store.save(initial))
        assertEquals(initial, store.load(PersistedState(), conv))
        store.wipe()
        assertEquals(PersistedState(), store.load(PersistedState(), conv))
        dir.deleteRecursively()
    }
}

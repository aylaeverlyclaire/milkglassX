package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.Overlay
import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.poster.PosterStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ProfileLogicTest {
    private val t0 = 1_700_000_000_000L
    private fun ctl() = AppController(
        TestSupport.data, PosterStore.load { TestSupport.read("poster_ops.json") },
        store = null, bootMs = t0, clock = { t0 }, zone = TimeZone.getTimeZone("Asia/Jakarta"),
        seedState = TestSeed.state,
    ).also { it.persisted = it.persisted.copy(isAuthenticated = 1) }

    @Test fun nameValidation() {
        val c = ctl(); val tr = c.tr
        c.openEditField("nm")
        assertFalse(c.saveEditField("nm", "   "))
        assertEquals(tr.t("profile.nameEmpty"), c.editError)
        assertFalse(c.saveEditField("nm", "a<b"))
        assertEquals(tr.t("profile.invalidChars"), c.editError)
        val other = c.users.entries.first { it.key != "me" }.value.name
        assertFalse(c.saveEditField("nm", other.uppercase()))
        assertEquals(tr.t("profile.nameTaken"), c.editError)
        assertTrue(c.saveEditField("nm", "  Sakura  "))
        assertEquals("Sakura", c.persisted.displayName)
        assertEquals("Sakura", c.meName)
        assertNull(c.overlay)
    }

    @Test fun usernameValidation() {
        val c = ctl(); val tr = c.tr
        assertFalse(c.saveEditField("hd", "@ab"))
        assertEquals(tr.t("profile.usernameError"), c.editError)
        assertFalse(c.saveEditField("hd", "nama pengguna"))
        assertTrue(c.saveEditField("hd", "@Kamu.Id_1"))
        assertEquals("kamu.id_1", c.persisted.handle)
        assertEquals("kamu.id_1", c.meHandle)
    }

    @Test fun deleteAccountNeedsTypedWord() {
        val c = ctl()
        c.confirmDeleteAccount()
        assertTrue(c.overlay is Overlay.DeleteAccount)
        c.deleteAccount("hapus saja")
        assertEquals(c.tr.t("settings.typeDeleteCaps"), c.deleteError)
        assertEquals(1, c.persisted.isAuthenticated)
        c.deleteAccount("HAPUS")
        assertEquals(0, c.persisted.isAuthenticated)
        assertEquals(t0 + 30 * DAY_MS, c.persisted.deletionScheduledAt)
        assertEquals(c.tr.t("settings.deletionScheduled"), c.toast?.text)
    }

    @Test fun signOutAndSettings() {
        val c = ctl()
        c.openDetail(0)
        c.signOut()
        assertEquals(0, c.persisted.isAuthenticated)
        assertEquals(0, c.viewStack.size)
        c.chooseSettingOption("theme", "dark")
        assertEquals("dark", c.persisted.settings.theme)
    }

    @Test fun pushIsIdempotent() {
        val c = ctl()
        c.openSearch(); c.openSearch()
        assertEquals(1, c.viewStack.size)
        c.openDetail(0); c.openDetail(0)
        assertEquals(2, c.viewStack.size)
    }

    @Test fun fireworksGuard() {
        val c = ctl()
        c.launchFireworks(); c.launchFireworks()
        assertEquals(1, c.fireworksToken)
    }
}

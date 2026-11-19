package com.milkglass.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlayerImageLockTest {
    private fun source(name: String): String {
        val root = listOf("src/main/java", "app/src/main/java").map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("Folder sumber tidak ditemukan dari ${File(".").absolutePath}")
        return root.walkTopDown().first { it.isFile && it.name == name }.readText()
    }

    private val code: String get() = source("PlayerScreen.kt") + "\n" + source("PlayerParts.kt")

    @Test
    fun videoAreaClipsToItsBounds() {
        assertTrue("VideoArea wajib memakai clipToBounds()", "clipToBounds()" in source("PlayerScreen.kt"))
    }

    @Test
    fun posterDoesNotMove() {

        assertFalse("Ken Burns tidak boleh kembali", "KenBurns" in code)
        val moving = Regex("""graphicsLayer\s*\{[^}]*(scaleX|scaleY|translationX|translationY)""")
        val hits = moving.findAll(source("PlayerParts.kt")).count()
        assertTrue("Tidak boleh ada graphicsLayer berskala/bergeser di PlayerParts.kt ($hits)", hits == 0)
    }

    @Test
    fun noPermanentDimmingOverPoster() {
        assertFalse("Vinyet menggelapkan tepi gambar; jangan dipakai di area video", "videoVignette" in code)
    }

    @Test
    fun hiddenBottomBarDoesNotSwallowTaps() {

        assertTrue(
            "pointerInput bar bawah wajib memakai e.faded sebagai kunci dan syarat",
            Regex("""pointerInput\(e\.faded\)\s*\{\s*if \(!e\.faded\)""").containsMatchIn(source("PlayerScreen.kt")),
        )
    }
}

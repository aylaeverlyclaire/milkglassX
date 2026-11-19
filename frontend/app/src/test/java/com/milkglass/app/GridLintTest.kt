package com.milkglass.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GridLintTest {
    private val literal = Regex("""(?<![\w.])(\d+(?:\.\d+)?)\.(?:dp|cssDp)\b""")
    private val allowed = setOf(0f, 1f, 2f, 999f)

    private fun sourceRoot(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it) }.firstOrNull { it.isDirectory }
            ?: error("Folder sumber tidak ditemukan dari ${File(".").absolutePath}")

    @Test
    fun noOffGridDpLiterals() {
        val skip = setOf("MilkGrid.kt", "MilkUnits.kt", "MilkIcons.kt")
        val violations = mutableListOf<String>()
        var scanned = 0
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in skip }.forEach { f ->
            scanned++
            f.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//") || "grid-exempt" in raw) return@forEachIndexed
                literal.findAll(raw.substringBefore("//")).forEach { m ->
                    val v = m.groupValues[1].toFloat()
                    if (v !in allowed && v % 4f != 0f) violations += "${f.name}:${i + 1}: ${m.value}"
                }
            }
        }
        assertTrue("Tidak ada file Kotlin yang dipindai", scanned > 0)
        assertTrue("Literal di luar grid 4dp:\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}

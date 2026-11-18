package com.milkglass.app

import com.milkglass.app.core.app.GENRES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocaleKeysTest {
    private fun srcDir(): File =
        listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory }

    private val messages get() = TestSupport.data.locale.messages

    
    private val keyCall = Regex("""(?<![A-Za-z0-9_])t\("([A-Za-z0-9_.\-]+)"""")

    /** Kunci yang dirangkai dalam `when`/`if` lalu dilempar ke t(...): literal berpola "grup.nama" milik grup locale. */
    private fun groups() = messages.keys.map { it.substringBefore('.') }.toSet()

    private fun kotlinFiles() = srcDir().walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun everyLiteralKeyExists() {
        val missing = mutableListOf<String>()
        kotlinFiles().forEach { f ->
            keyCall.findAll(f.readText()).forEach { m ->
                if (m.groupValues[1] !in messages) missing += "${f.name}: ${m.groupValues[1]}"
            }
        }
        assertTrue("Kunci teks hilang (akan tampil mentah di layar): $missing", missing.isEmpty())
    }

    @Test fun everyKeyLikeLiteralExists() {
        val g = groups()
        val lit = Regex(""""([a-z][A-Za-z]*)\.([A-Za-z][A-Za-z0-9\-]*)"""")
        val missing = mutableListOf<String>()
        kotlinFiles().forEach { f ->
            lit.findAll(f.readText()).forEach { m ->
                val key = "${m.groupValues[1]}.${m.groupValues[2]}"
                if (m.groupValues[1] in g && key !in messages) missing += "${f.name}: $key"
            }
        }
        assertTrue("Literal berpola kunci tidak ada di locale: $missing", missing.isEmpty())
    }

    @Test fun everyPlaceholderIsSupplied() {
        val ph = Regex("""\{([A-Za-z_]+)\}""")
        val bad = mutableListOf<String>()
        kotlinFiles().forEach { f ->
            val s = f.readText()
            keyCall.findAll(s).forEach { m ->
                val key = m.groupValues[1]
                val text = messages[key] ?: return@forEach
                val need = ph.findAll(text).map { it.groupValues[1] }.toSet()
                if (need.isEmpty()) return@forEach
                // Ambil argumen panggilan sampai kurung penutup yang seimbang.
                var depth = 1
                var i = m.range.last + 1
                while (i < s.length && depth > 0) {
                    if (s[i] == '(') depth++ else if (s[i] == ')') depth--
                    i++
                }
                val args = s.substring(m.range.last + 1, i - 1)
                val given = Regex(""""([A-Za-z_]+)"\s+to\b""").findAll(args).map { it.groupValues[1] }.toSet()
                if (!given.containsAll(need)) bad += "${f.name}: $key butuh $need, dikirim $given"
            }
        }
        assertTrue("Placeholder tak terisi (akan tampil sebagai {nama}): $bad", bad.isEmpty())
    }

    @Test fun enumLabelsExist() {
        (GENRES + "all").forEach { assertTrue("genre.$it", "genre.$it" in messages) }
        listOf("home", "schedule", "collection", "social", "profile").forEach { assertTrue("mainTab.$it", "mainTab.$it" in messages) }
        listOf("episodes", "similar", "comments").forEach { assertTrue("detailTab.$it", "detailTab.$it" in messages) }
        listOf("watchlist", "history", "completed").forEach { assertTrue("collectionTab.$it", "collectionTab.$it" in messages) }
        listOf("system", "light", "dark").forEach { assertTrue("theme.$it", "theme.$it" in messages) }
    }

    @Test fun missingKeyNeverLeaksRawCode() {
        val seen = mutableListOf<String>()
        val tr = com.milkglass.app.core.data.Translator(TestSupport.data.locale) { seen += it }
        assertEquals("", tr.t("player.doesNotExist"))
        assertEquals("", tr.t("player.doesNotExist", "x" to 1))
        // {param} yang tak terisi dibuang, bukan ditampilkan.
        assertEquals("Ep", tr.t("home.episodePrefix", "wrong" to 1))
        assertEquals(listOf("player.doesNotExist", "player.doesNotExist", "home.episodePrefix"), seen)
    }

    @Test fun deleteWordMatchesWhatTheScreenAsks() {
        assertEquals("HAPUS", messages["settings.deleteConfirmWord"])
        assertTrue(messages.getValue("settings.deleteAccountBody").contains("HAPUS"))
    }
}

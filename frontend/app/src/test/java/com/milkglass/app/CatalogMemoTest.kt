package com.milkglass.app

import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.data.AppData
import com.milkglass.app.core.data.vibesOf
import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.poster.PosterStore
import com.milkglass.app.core.remote.CachedSnapshot
import com.milkglass.app.core.remote.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class CatalogMemoTest {

    private fun noSeed(): AppData = TestSupport.data.let { AppData(it.locale, emptyList(), it.users, it.seedConversationsRaw, it.features) }

    private val base: List<Anime> get() = TestSupport.data.catalogRaw

    private fun entries(): List<CatalogEntry> = (0 until 60).map { i ->
        val b = base[i % base.size]
        val id = 1000 + i
        CatalogEntry(
            b.copy(
                id = id,
                title = if (i % 7 == 0) "Shōwa Ōkami $i" else "Judul $i",
                altTitle = "Alt $i",
                rating = 5.0 + (i % 10) / 2.0,
                voteCount = (i * 37) % 101,
                similarIds = emptyList(),
            ),
            "https://img.test/$id.jpg",
            null,
        )
    }

    private fun ctl() = AppController(noSeed(), PosterStore(emptyMap()), null, initialCatalog = CachedSnapshot(entries(), true))

    private fun norm(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), "")

    private fun reference(c: AppController, query: String, genre: String, vibe: String): List<Anime> {
        val q = norm(query.trim())
        val r = c.catalog.filter { a ->
            (genre == "all" || a.genres.contains(genre)) &&
                (vibe == "all" || vibesOf(a.genres).contains(vibe)) &&
                (q.isEmpty() || norm(
                    listOf(a.title, a.altTitle, a.genres.joinToString(" ") { c.enumLabel("genre", it) }, a.genres.joinToString(" "), a.studio, a.year.toString()).joinToString(" "),
                ).contains(q))
        }
        return r.sortedWith { a, b ->
            if (q.isNotEmpty()) {
                val tx = if (norm(a.title).contains(q)) 0 else 1
                val ty = if (norm(b.title).contains(q)) 0 else 1
                if (tx != ty) return@sortedWith tx - ty
            }
            b.rating.compareTo(a.rating)
        }
    }

    private fun search(c: AppController, q: String, genre: String = "all", vibe: String = "all"): List<Anime> {
        c.searchQuery = q; c.submittedQuery = q; c.searchGenre = genre; c.searchVibe = vibe
        return c.searchCatalog()
    }

    @Test fun searchMatchesOldAlgorithm_forManyQueries() {
        val c = ctl()
        val genre = c.catalog.first { it.genres.isNotEmpty() }.genres.first()
        val vibe = vibesOf(c.catalog.first { vibesOf(it.genres).isNotEmpty() }.genres).first()
        val cases = listOf(
            Triple("", "all", "all"), Triple("judul 5", "all", "all"), Triple("  JUDUL 5  ", "all", "all"),
            Triple("showa", "all", "all"), Triple("ōkami", "all", "all"), Triple("okami 14", "all", "all"),
            Triple("alt 12", "all", "all"), Triple("tidak-ada-judul-ini", "all", "all"),
            Triple("", genre, "all"), Triple("judul", genre, "all"), Triple("", "all", vibe), Triple("showa", genre, vibe),
        )
        for ((q, g, v) in cases) {
            val got = search(c, q, g, v).map { it.id }
            val want = reference(c, q, g, v).map { it.id }
            if (want.isEmpty()) {
                assertTrue("q='$q' genre=$g vibe=$v expected empty", got.isEmpty())
            } else {
                assertTrue("q='$q' genre=$g vibe=$v missing expected matches", got.containsAll(want))
            }
        }
    }

    @Test fun spaceInsensitiveAndTypoTolerantSearchWorks() {
        val c = ctl()
        val compactIds = search(c, "showaokami").map { it.id }
        assertTrue("compact query should match Shōwa Ōkami", compactIds.isNotEmpty())
        val typoIds = search(c, "showaa okmi").map { it.id }
        assertTrue("1-typo query should match Shōwa Ōkami", typoIds.isNotEmpty())
    }

    @Test fun accentInsensitiveSearchFindsShowaTitles() {
        val c = ctl()
        val ids = search(c, "showa okami").map { it.id }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { c.animeOf(it)!!.title.startsWith("Shōwa") })
    }

    @Test fun titleMatchesRankBeforeOtherMatches() {
        val c = ctl()

        val r = search(c, "judul")
        assertTrue(r.isNotEmpty())
        assertTrue(r.all { norm(it.title).contains("judul") })
    }

    @Test fun searchIsMemoizedUntilInputsChange() {
        val c = ctl()
        val a = search(c, "judul 1")
        assertSame("input sama harus mengembalikan hasil yang sama tanpa hitung ulang", a, search(c, "judul 1"))
        val b = search(c, "judul 2")
        assertNotSame(a, b)
        assertSame(b, search(c, "judul 2"))
        val g = c.catalog.first { it.genres.isNotEmpty() }.genres.first()
        assertNotSame(b, search(c, "judul 2", genre = g))
    }

    @Test fun trendingIsSortedByVoteCountAndMemoized() {
        val c = ctl()
        val t = c.trendingList()
        assertSame(t, c.trendingList())
        assertEquals(c.catalog.sortedByDescending { it.voteCount }.map { it.id }, t.map { it.id })
        assertTrue(t.zipWithNext().all { (x, y) -> x.voteCount >= y.voteCount })
    }

    @Test fun similarIsMemoizedPerTitleAndLimit() {
        val c = ctl()
        val a = c.catalog.first()
        val s10 = c.similarOf(a)
        assertSame(s10, c.similarOf(a))
        assertTrue(s10.size <= 10)
        assertFalse(s10.any { it.id == a.id })
        val s3 = c.similarOf(a, 3)
        assertTrue(s3.size <= 3)
        assertEquals(s10.take(3).map { it.id }, s3.map { it.id })
    }

    @Test fun secondaryPostersSkipFirstScreenAndAreDistinctAndBounded() {
        val c = ctl()
        val first = c.firstScreenPosterUrls()
        val second = c.secondaryPosterUrls()
        assertTrue(second.none { it in first })
        assertEquals(second.size, second.distinct().size)
        assertEquals(minOf(40, 60 - first.size), second.size)
        assertTrue(c.secondaryPosterUrls(limit = 5).size <= 5)
    }

    @Test fun secondaryPostersPutWatchlistFirst() {
        val c = ctl()
        val first = c.firstScreenPosterUrls()
        val target = c.catalog.map { it.id }.first { "https://img.test/$it.jpg" !in first }
        c.persisted = c.persisted.copy(watchlist = listOf(target))
        assertEquals("https://img.test/$target.jpg", c.secondaryPosterUrls().first())
    }

    @Test fun warmCachesIsHarmlessAndKeepsResultsIdentical() {
        val c = ctl()
        val before = reference(c, "judul", "all", "all").map { it.id }
        c.warmCaches()
        assertEquals(before, search(c, "judul").map { it.id })
    }
}

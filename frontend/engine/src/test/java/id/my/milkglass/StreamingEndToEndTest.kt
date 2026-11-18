package id.my.milkglass

import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.extractor.JsUnpacker
import id.my.milkglass.player.ErrorMapper
import id.my.milkglass.player.PlayerManager
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.StreamResolutionException
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.IndexedAnimeEntry
import id.my.milkglass.source.SourceAvailabilityIndex
import id.my.milkglass.source.SourceSlugCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingEndToEndTest {

    @Test
    fun slugBuilderProducesNormalizedEpisodeSlug() {
        val slug = StreamResolver.buildEpisodeSlug("Jujutsu Kaisen Season 3", 5)
        assertEquals("jujutsu-kaisen-season-3-ep-5", slug)
    }

    @Test
    fun sourcePriorityOrderMatchesFourSourceCascade() {
        assertEquals(1, SourcePriority.OTAKUDESU.rank)
        assertEquals(2, SourcePriority.SAMEHADAKU.rank)
        assertEquals(3, SourcePriority.OPLOVERZ.rank)
        assertEquals(4, SourcePriority.KUSONIME.rank)
    }

    @Test
    fun mkvQualityParsesStandardAndSiteAliases() {
        assertEquals(MkvQuality.P1080, MkvQuality.fromText("MKV 1080p"))
        assertEquals(MkvQuality.P1080, MkvQuality.fromText("FULLHD"))
        assertEquals(MkvQuality.P1080, MkvQuality.fromText("FHD"))
        assertEquals(MkvQuality.P720, MkvQuality.fromText("MKV 720p"))
        assertEquals(MkvQuality.P720, MkvQuality.fromText("HD"))
        assertEquals(MkvQuality.P480, MkvQuality.fromText("MKV 480p"))
        assertEquals(MkvQuality.P480, MkvQuality.fromText("Mini"))
    }

    @Test
    fun hosterFilterPrioritizesPixeldrainAndBlocksMega() {
        assertTrue(HosterFilter.isBlacklisted("Mega", "https://mega.nz/file/123"))
        assertTrue(HosterFilter.isBlacklisted("Otakufiles", "https://otakufiles.net/abc"))
        assertFalse(HosterFilter.isBlacklisted("Pdrain", "https://pixeldrain.com/u/abc"))
        assertTrue(
            HosterFilter.rank("Pdrain", "https://pixeldrain.com/u/abc") <
                HosterFilter.rank("KFiles", "https://krakenfiles.com/view/xyz/file.html")
        )
    }

    @Test
    fun jsUnpackerDecodesPackedEmbedScript() {
        val packed = "\u007D('0 1=\"2://3.4/5.6\";',7,7,'var|src|https|cdn|example|ep1|mkv'.split('|'),0,{}))"
        val unpacked = JsUnpacker.unpack(packed)
        assertNotNull(unpacked)
        assertTrue(unpacked!!.contains("https://cdn.example/ep1.mkv"))
    }

    @Test
    fun slugCacheStoresAndInvalidatesInMemory() {
        val cache = SourceSlugCache(null)
        cache.put(SourcePriority.OTAKUDESU, "Jujutsu Kaisen", "https://otakudesu.blog/anime/jjk-sub-indo/")
        assertEquals(
            "https://otakudesu.blog/anime/jjk-sub-indo/",
            cache.get(SourcePriority.OTAKUDESU, "Jujutsu Kaisen")
        )
        cache.invalidate(SourcePriority.OTAKUDESU, "Jujutsu Kaisen")
        assertEquals(null, cache.get(SourcePriority.OTAKUDESU, "Jujutsu Kaisen"))
    }

    @Test
    fun errorMapperPreservesStreamResolutionReason() {
        val err = StreamResolutionException(FailureReason.HTTP_403, "Cloudflare challenge")
        assertEquals(FailureReason.HTTP_403, ErrorMapper.classifyThrowable(err))
    }

    @Test
    fun subtitleSanitizerDropsAssVectorDrawingsAndBlankLines() {
        val raw = "m 0 0 l 235.5 0 l 215.5 108 l 0 10\n\nMemastikan kau tetap hidup\\N\\Ndan bersekolah"
        val lines = PlayerManager.sanitizeSubtitleText(raw)
        assertEquals(listOf("Memastikan kau tetap hidup", "dan bersekolah"), lines)
    }

    @Test
    fun availabilityIndexMatchesPresentTitlesAndRejectsMissingOnes() {
        val index = SourceAvailabilityIndex(null)
        val entries = (1..20).map { i ->
            IndexedAnimeEntry(
                source = SourcePriority.OTAKUDESU,
                title = if (i == 1) "Tantei wa Mou Shindeiru Season 2" else "Sample Anime $i",
                normalizedTitle = if (i == 1) "tantei wa mou shindeiru season 2" else "sample anime $i",
                tokens = if (i == 1) setOf("tantei", "wa", "mou", "shindeiru", "season") else setOf("sample", "anime", "$i"),
                url = "https://otakudesu.blog/anime/sample-$i/"
            )
        }
        index.loadEntriesForTest(entries)
        assertTrue(index.isReady)
        assertTrue(index.isAvailable(listOf("Tantei wa mou, Shindeiru. Season 2")))
        assertFalse(index.isAvailable(listOf("Anime Fiktif Yang Tidak Ada Di Sumber")))
    }
}

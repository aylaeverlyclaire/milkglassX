package id.my.milkglass

import id.my.milkglass.extractor.HosterFilter
import id.my.milkglass.extractor.JsUnpacker
import id.my.milkglass.player.ErrorMapper
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.StreamResolutionException
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.SourcePriority
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
}

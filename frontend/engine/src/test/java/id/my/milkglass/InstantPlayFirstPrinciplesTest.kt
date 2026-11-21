package id.my.milkglass

import id.my.milkglass.extractor.EmbedMediaSniffer
import id.my.milkglass.net.IsolatedHttpClients
import id.my.milkglass.source.MediaContainer
import id.my.milkglass.source.MkvCascadeResolver
import id.my.milkglass.source.MkvQuality
import id.my.milkglass.source.RawCandidateStore
import id.my.milkglass.source.RawMkvCandidate
import id.my.milkglass.source.SourcePriority
import id.my.milkglass.source.TitleMatcher
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantPlayFirstPrinciplesTest {

    @Test
    fun titleMatcherEnforcesLawOfIdentityAcrossSeasonsAndParts() {
        assertTrue(
            "Season 1 tidak boleh cocok dengan Season IV",
            TitleMatcher.score("Date A Live", "Date A Live IV") < 0.40
        )
        assertTrue(
            "Season 1 tidak boleh cocok dengan Season V",
            TitleMatcher.score("Date A Live", "Date A Live V") < 0.40
        )
        assertTrue(
            "Season 1 harus cocok dengan rilisan BD base series",
            TitleMatcher.score("Date A Live", "Date A Live BD (Episode 1 - 12)") >= 0.70
        )
        assertTrue(
            "Eksplisit Season 1 harus cocok dengan base series tanpa nomor",
            TitleMatcher.score("Date A Live Season 1", "Date A Live") >= 0.70
        )
        assertTrue(
            "Season 2 tidak boleh cocok dengan base series Season 1",
            TitleMatcher.score("Date A Live Season 2", "Date A Live") < 0.40
        )
        assertTrue(
            "Base series tidak boleh cocok dengan Part 2",
            TitleMatcher.score("Attack on Titan", "Attack on Titan Part 2") < 0.50
        )
    }

    @Test
    fun embedMediaSnifferFiltersPlayableMediaAndBlocksAds() {
        assertTrue(EmbedMediaSniffer.isPlayableMediaUrl("https://cdn.example.com/hls/master.m3u8?token=abc"))
        assertTrue(EmbedMediaSniffer.isPlayableMediaUrl("https://cdn.example.com/video/ep1.mp4"))
        assertTrue(EmbedMediaSniffer.isPlayableMediaUrl("https://rr1---sn.googlevideo.com/videoplayback?id=123"))
        assertTrue(EmbedMediaSniffer.isPlayableMediaUrl("https://blogger.googleusercontent.com/video-play?id=xyz"))
        assertTrue(EmbedMediaSniffer.isPlayableMediaUrl("https://video-downloads.googleusercontent.com/abc123"))
        assertTrue(EmbedMediaSniffer.isPlayableMediaUrl("https://pixeldrain.com/api/file/abc123"))
        assertFalse(EmbedMediaSniffer.isPlayableMediaUrl("https://embed.example.com/player.php?file=ep1.mp4&type=video"))
        assertFalse(EmbedMediaSniffer.isPlayableMediaUrl("https://embed.example.com/watch.html?mime=video"))
        assertFalse(EmbedMediaSniffer.isPlayableMediaUrl("https://qq288pmk.com/promo/video.mp4"))
        assertFalse(EmbedMediaSniffer.isPlayableMediaUrl("https://cdn.example.com/ads/preroll.mp4"))
        assertFalse(EmbedMediaSniffer.isPlayableMediaUrl("https://cdn.example.com/poster.jpg"))

        assertTrue(EmbedMediaSniffer.isBlockedAdOrAssetUrl("https://qq288pmk.com/landing"))
        assertTrue(EmbedMediaSniffer.isBlockedAdOrAssetUrl("https://pagead2.googlesyndication.com/pagead/js"))
        assertTrue(EmbedMediaSniffer.isBlockedAdOrAssetUrl("https://cdn.example.com/style.css"))
        assertFalse(EmbedMediaSniffer.isBlockedAdOrAssetUrl("https://desustream.info/dstream/ondesu/v5/index.php?id=xyz"))

        assertTrue(EmbedMediaSniffer.isHlsUrl("https://cdn.example.com/index.m3u8?exp=999"))
        assertFalse(EmbedMediaSniffer.isHlsUrl("https://cdn.example.com/video.mp4"))
        assertEquals("https://desustream.info", EmbedMediaSniffer.extractOrigin("https://desustream.info/dstream/v5/"))
    }

    @Test
    fun rankCandidatesPrioritizesStreamingEmbedsForPlaybackAndDownloadsForDownloadMode() {
        val resolver = MkvCascadeResolver(clients = IsolatedHttpClients(null), providers = emptyList())
        val downloadHoster = RawMkvCandidate(
            source = SourcePriority.OTAKUDESU,
            quality = MkvQuality.P1080,
            hosterLabel = "Pixeldrain",
            rawUrl = "https://pixeldrain.com/u/dead123",
            pageReferer = "https://otakudesu.blog/episode/dal-episode-1-sub-indo/",
            container = MediaContainer.MKV,
            isStreamEmbed = false
        )
        val streamEmbed = RawMkvCandidate(
            source = SourcePriority.OTAKUDESU,
            quality = MkvQuality.P720,
            hosterLabel = "OtakudesuEmbed",
            rawUrl = "https://desustream.info/dstream/ondesu/v5/index.php?id=abc",
            pageReferer = "https://otakudesu.blog/episode/dal-episode-1-sub-indo/",
            container = MediaContainer.MP4,
            isStreamEmbed = true
        )

        val playRanked = resolver.rankCandidates(
            candidates = listOf(downloadHoster, streamEmbed),
            qualityOrder = MkvQuality.ALL_DESC,
            allowBatchFallback = false,
            preferCompatibleContainer = false,
            preferStreamingEmbeds = true
        )
        assertEquals(streamEmbed.rawUrl, playRanked.first().rawUrl)

        val downloadRanked = resolver.rankCandidates(
            candidates = listOf(downloadHoster, streamEmbed),
            qualityOrder = MkvQuality.ALL_DESC,
            allowBatchFallback = false,
            preferCompatibleContainer = false,
            preferStreamingEmbeds = false
        )
        assertEquals(downloadHoster.rawUrl, downloadRanked.first().rawUrl)
    }

    @Test
    fun rawCandidateStorePreservesIsStreamEmbedFlag() {
        val original = listOf(
            RawMkvCandidate(
                source = SourcePriority.OTAKUDESU,
                quality = MkvQuality.P720,
                hosterLabel = "OtakudesuEmbed",
                rawUrl = "https://desustream.info/dstream/v5/?id=1",
                pageReferer = "https://otakudesu.blog/episode/dal-episode-1-sub-indo/",
                container = MediaContainer.MP4,
                isStreamEmbed = true
            ),
            RawMkvCandidate(
                source = SourcePriority.OTAKUDESU,
                quality = MkvQuality.P720,
                hosterLabel = "Solidfiles",
                rawUrl = "https://www.solidfiles.com/v/abc",
                pageReferer = "https://otakudesu.blog/episode/dal-episode-1-sub-indo/",
                container = MediaContainer.MKV,
                isStreamEmbed = false
            )
        )
        val decoded = RawCandidateStore.decodeList(JSONArray(RawCandidateStore.encodeList(original).toString()))
        assertEquals(original, decoded)
    }
}

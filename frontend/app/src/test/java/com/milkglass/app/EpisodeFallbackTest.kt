package com.milkglass.app

import com.milkglass.app.core.remote.ChainedEpisodeMetaSource
import com.milkglass.app.core.remote.EpisodeMeta
import com.milkglass.app.core.remote.EpisodeMetaSource
import com.milkglass.app.core.remote.parseAniListBanners
import com.milkglass.app.core.remote.parseAniListStreaming
import com.milkglass.app.core.remote.parseAniZipEpisodes
import com.milkglass.app.core.remote.parseJikanEpisodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeFallbackTest {
    private class Fake(val result: Map<Int, EpisodeMeta>?) : EpisodeMetaSource {
        var calls = 0
        override suspend fun load(anilistId: Int): Map<Int, EpisodeMeta>? { calls++; return result }
    }

    @Test fun backtickAndCurlyQuotesBecomeApostrophe() {
        val json = """{"episodes":{"1":{"title":{"en":"That`s How  Love Starts, Ya Know!"}}}}"""
        assertEquals("That's How Love Starts, Ya Know!", parseAniZipEpisodes(json)!![1]!!.title)
    }

    @Test fun aniListStreamingSplitsNumberAndTitle() {
        val json = """{"data":{"Media":{"idMal":57334,"streamingEpisodes":[
            {"title":"Episode 1 - That's How Love Starts, Ya Know!","thumbnail":"https://x/1.jpg"},
            {"title":"Episode 2 - Why Would You Say That","thumbnail":null},
            {"title":"Trailer","thumbnail":"https://x/t.jpg"}]}}}"""
        val p = parseAniListStreaming(json)!!
        assertEquals(57334, p.malId)
        assertEquals(
            mapOf(
                1 to EpisodeMeta("That's How Love Starts, Ya Know!", "https://x/1.jpg"),
                2 to EpisodeMeta("Why Would You Say That", null),
            ),
            p.episodes,
        )
        assertNull(parseAniListStreaming("bukan json"))
    }

    @Test fun jikanUsesMalIdAsEpisodeNumberAndReadsPaging() {
        val json = """{"pagination":{"has_next_page":true},"data":[
            {"mal_id":1,"title":"Judul Satu"},{"mal_id":2,"title":"Episode 2"},{"mal_id":3,"title":"Judul Tiga"}]}"""
        val p = parseJikanEpisodes(json)!!
        assertEquals(mapOf(1 to "Judul Satu", 3 to "Judul Tiga"), p.titles)
        assertTrue(p.hasNext)
        assertNull(parseJikanEpisodes("{}"))
    }

    @Test fun bannersKeepOnlyHttpsLandscapeUrls() {
        val json = """{"data":{"Page":{"media":[
            {"id":1,"bannerImage":"https://s4.anilist.co/b1.jpg"},
            {"id":2,"bannerImage":null},
            {"id":3,"bannerImage":"http://s4.anilist.co/b3.jpg"}]}}}"""
        assertEquals(mapOf(1 to "https://s4.anilist.co/b1.jpg", 3 to "https://s4.anilist.co/b3.jpg"), parseAniListBanners(json))
        assertNull(parseAniListBanners("bukan json"))
    }

    @Test fun chainSkipsFallbackWhenPrimaryIsComplete() = runBlocking {
        val primary = Fake(mapOf(1 to EpisodeMeta("A", null), 2 to EpisodeMeta("B", null)))
        val fallback = Fake(mapOf(1 to EpisodeMeta("Z", null)))
        val r = ChainedEpisodeMetaSource(primary, fallback).loadFor(1, 2)
        assertEquals("A", r!![1]!!.title)
        assertEquals(0, fallback.calls)
    }

    @Test fun chainFillsGapsAndKeepsPrimaryImages() = runBlocking {
        val primary = Fake(mapOf(1 to EpisodeMeta("A", "https://x/1.jpg"), 2 to EpisodeMeta(null, "https://x/2.jpg")))
        val fallback = Fake(mapOf(2 to EpisodeMeta("B", null), 3 to EpisodeMeta("C", null)))
        val r = ChainedEpisodeMetaSource(primary, fallback).loadFor(1, 3)!!
        assertEquals(EpisodeMeta("A", "https://x/1.jpg"), r[1])
        assertEquals(EpisodeMeta("B", "https://x/2.jpg"), r[2])
        assertEquals(EpisodeMeta("C", null), r[3])
    }

    @Test fun chainUsesFallbackWhenPrimaryFails() = runBlocking {
        val fallback = Fake(mapOf(1 to EpisodeMeta("A", null)))
        assertEquals(mapOf(1 to EpisodeMeta("A", null)), ChainedEpisodeMetaSource(Fake(null), fallback).loadFor(1, 1))
        assertNull(ChainedEpisodeMetaSource(Fake(null), Fake(null)).loadFor(1, 1))
    }
}

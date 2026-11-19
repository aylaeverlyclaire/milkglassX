package com.milkglass.app

import com.milkglass.app.core.app.SkipKind
import com.milkglass.app.core.app.SkipPrompter
import com.milkglass.app.core.remote.EpisodeMeta
import com.milkglass.app.core.remote.SkipTimes
import com.milkglass.app.core.remote.SkipWindow
import com.milkglass.app.core.remote.parseAniSkip
import com.milkglass.app.core.remote.parseAniZipEpisodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeMetaTest {
    @Test fun readsTitlesAndImagesOfNumberedEpisodesOnly() {
        val json = """{"episodes":{
            "1":{"image":"https://x/1.jpg","title":{"en":"Cruelty","ja":"Zankoku-ja"}},
            "2":{"image":"http://x/2.jpg","title":{"ja":"Nihongo"}},
            "3":{"title":{"en":"Episode 3"}},
            "4":{"image":null},
            "5":{"title":{"x-jat":"Romaji"}},
            "S1":{"image":"https://x/s.jpg"},"0":{"image":"https://x/0.jpg"}}}"""
        assertEquals(
            mapOf(
                1 to EpisodeMeta("Cruelty", "https://x/1.jpg"),
                2 to EpisodeMeta("Nihongo", "https://x/2.jpg"),
                5 to EpisodeMeta("Romaji", null),
            ),
            parseAniZipEpisodes(json),
        )
    }

    @Test fun missingEpisodesIsEmptyAndBadJsonIsFailure() {
        assertEquals(emptyMap<Int, EpisodeMeta>(), parseAniZipEpisodes("""{"titles":{}}"""))
        assertNull(parseAniZipEpisodes("bukan json"))
    }

    @Test fun aniSkipReadsOpeningAndEnding() {
        val json = """{"found":true,"statusCode":200,"results":[
            {"interval":{"startTime":12.5,"endTime":101.2},"skipType":"op","episodeLength":1420},
            {"interval":{"startTime":1300.0,"endTime":1390.0},"skipType":"ed"},
            {"interval":{"startTime":50.0,"endTime":40.0},"skipType":"op"}]}"""
        assertEquals(SkipTimes(SkipWindow(12.5, 101.2), SkipWindow(1300.0, 1390.0)), parseAniSkip(json))
    }

    @Test fun aniSkipNotFoundIsNoneAndBadJsonIsFailure() {
        assertEquals(SkipTimes.NONE, parseAniSkip("""{"found":false,"results":[],"statusCode":404}"""))
        assertNull(parseAniSkip("bukan json"))
    }

    @Test fun promptShowsEightSecondsThenHidesEvenInsideRange() {
        val p = SkipPrompter()
        val t = SkipTimes(SkipWindow(10.0, 90.0), SkipWindow(1300.0, 1390.0))
        assertNull(p.update(t, 5.0, .25))
        assertEquals(SkipKind.Opening, p.update(t, 10.0, .25))
        var pos = 10.0
        repeat(31) { pos += .25; assertEquals(SkipKind.Opening, p.update(t, pos, .25)) }
        pos += .25
        assertNull(p.update(t, pos, .25))
        pos += .25
        assertNull(p.update(t, pos, .25))
    }

    @Test fun promptReappearsAfterLeavingAndReentering() {
        val p = SkipPrompter()
        val t = SkipTimes(SkipWindow(10.0, 90.0), null)
        assertEquals(SkipKind.Opening, p.update(t, 10.0, .25))
        assertNull(p.update(t, 95.0, .25))
        assertEquals(SkipKind.Opening, p.update(t, 20.0, .25))
    }

    @Test fun promptEndsWithShortRangeAndDismissWorks() {
        val p = SkipPrompter()
        val t = SkipTimes(null, SkipWindow(100.0, 103.0))
        assertEquals(SkipKind.Ending, p.update(t, 100.0, .25))
        assertEquals(SkipKind.Ending, p.update(t, 102.75, .25))
        assertNull(p.update(t, 103.0, .25))
        assertEquals(SkipKind.Ending, p.update(t, 101.0, .25))
        p.dismiss()
        assertNull(p.update(t, 101.25, .25))
    }
}

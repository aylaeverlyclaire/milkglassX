package com.milkglass.app

import com.milkglass.app.core.remote.AniListExtras
import com.milkglass.app.core.remote.parseAniListExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListExtrasTest {
    @Test fun readsRecommendationsAndRealAiringSchedule() {
        val json = """{"data":{"Media":{
            "recommendations":{"nodes":[
              {"rating":40,"mediaRecommendation":{"id":11}},
              {"rating":-3,"mediaRecommendation":{"id":12}},
              {"rating":9,"mediaRecommendation":null},
              {"rating":7,"mediaRecommendation":{"id":11}},
              {"rating":2,"mediaRecommendation":{"id":13}}]},
            "airingSchedule":{"nodes":[
              {"episode":5,"airingAt":1700000000},
              {"episode":6,"airingAt":1700700000},
              {"episode":0,"airingAt":1}]}}}}"""
        assertEquals(
            AniListExtras(listOf(11, 13), mapOf(5 to 1_700_000_000_000L, 6 to 1_700_700_000_000L)),
            parseAniListExtras(json),
        )
    }

    @Test fun missingMediaIsEmptyAndBadJsonIsFailure() {
        assertEquals(AniListExtras(emptyList(), emptyMap()), parseAniListExtras("""{"data":{"Media":null}}"""))
        assertNull(parseAniListExtras("bukan json"))
    }
}

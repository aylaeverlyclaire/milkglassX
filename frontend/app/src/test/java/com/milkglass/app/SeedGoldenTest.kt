package com.milkglass.app

import com.milkglass.app.core.data.SeedData
import com.milkglass.app.core.data.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedGoldenTest {
    private val golden = Json.parseToJsonElement(TestSupport.resource("golden/seed_golden.json")).jsonObject
    private val boot = golden.getValue("boot").jsonPrimitive.long

    @Test fun nextAirAtMatchesJs() {
        val expected = golden.getValue("nextAirAt").jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.long }
        val actual = SeedData.withNextAirAt(TestSupport.data.catalogRaw, boot).map { it.nextAirAt }
        assertEquals(expected, actual)
    }

    @Test fun seedThreadMatchesJs() {
        val catalog = TestSupport.data.catalogRaw
        golden.getValue("threads").jsonArray.forEach { entry ->
            val o = entry.jsonObject
            val aid = o.getValue("aid").jsonPrimitive.content.toInt()
            val ep = o.getValue("ep").jsonPrimitive.content.toInt()
            val actual = SeedData.thread(catalog[aid], ep, boot, TestSupport.data.locale.commentTemplates)
            val exp = o.getValue("msgs").jsonArray
            assertEquals("jumlah ($aid,$ep)", exp.size, actual.size)
            exp.forEachIndexed { i, m ->
                val mo = m.jsonObject
                assertEquals(mo.getValue("id").jsonPrimitive.content, actual[i].id)
                assertEquals(mo.getValue("authorId").jsonPrimitive.content, actual[i].authorId)
                assertEquals(mo.getValue("text").jsonPrimitive.content, actual[i].text)
                assertEquals(mo.getValue("sentAt").jsonPrimitive.long, actual[i].sentAt)
            }
        }
    }
}

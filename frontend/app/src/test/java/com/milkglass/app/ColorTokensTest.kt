package com.milkglass.app

import androidx.compose.ui.graphics.Color
import com.milkglass.app.core.design.MilkDarkColors
import com.milkglass.app.core.design.MilkLightColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class ColorTokensTest {
    private fun hex(c: Color) = "#%02X%02X%02X".format((c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt())
    private fun norm(v: String): String {
        val h = v.trim().removePrefix("#")
        return "#" + (if (h.length == 3) h.map { "$it$it" }.joinToString("") else h).uppercase()
    }

    private fun check(mode: String, colors: Map<String, Color>) {
        val expected = Json.parseToJsonElement(TestSupport.resource("tokens_computed.json")).jsonObject[mode] as JsonObject
        assertEquals(expected.size, colors.size)
        expected.forEach { (k, v) -> assertEquals("$mode --$k", norm(v.jsonPrimitive.content), hex(colors.getValue(k))) }
    }

    @Test fun lightMatchesChromium() = check("light", MilkLightColors.asMap())
    @Test fun darkMatchesChromium() = check("dark", MilkDarkColors.asMap())
}

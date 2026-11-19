package com.milkglass.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isSpecified
import com.milkglass.app.core.design.MilkFonts
import com.milkglass.app.core.design.MilkGrid
import com.milkglass.app.core.design.MilkTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MilkGridTest {
    @Test
    fun snapRoundsToNearestUnitTiesUp() {
        assertEquals(8f, MilkGrid.snap(6f), 0f)
        assertEquals(12f, MilkGrid.snap(10f), 0f)
        assertEquals(16f, MilkGrid.snap(14f), 0f)
        assertEquals(4f, MilkGrid.snap(5f), 0f)
        assertEquals(44f, MilkGrid.snap(45f), 0f)
        assertEquals(0f, MilkGrid.snap(1f), 0f)
    }

    @Test
    fun isOnGrid() {
        listOf(0f, 4f, 8f, 12f, 16f, 52f, 96f).forEach { assertTrue("$it", MilkGrid.isOnGrid(it)) }
        listOf(1f, 3f, 6f, 10f, 14f, 38.4f, 95.59f).forEach { assertFalse("$it", MilkGrid.isOnGrid(it)) }
    }

    @Test
    fun breakpointsMapToColumns() {
        assertEquals(4, MilkGrid.columnsFor(360f))
        assertEquals(4, MilkGrid.columnsFor(599.9f))
        assertEquals(8, MilkGrid.columnsFor(600f))
        assertEquals(8, MilkGrid.columnsFor(839.9f))
        assertEquals(12, MilkGrid.columnsFor(840f))
    }

    @Test
    fun columnsAndGuttersFillTheContentWidthExactly() {
        for (w in listOf(320f, 360f, 393f, 412f, 600f, 768f, 840f, 1024f)) {
            val cols = MilkGrid.columnsFor(w)
            val full = MilkGrid.spanDp(cols, w)
            assertEquals("lebar $w", w - 2f * MilkGrid.Margin.value, full, 1e-3f)
        }

        assertEquals(70f, MilkGrid.columnWidthDp(360f), 1e-4f)

        assertEquals(156f, MilkGrid.spanDp(2, 360f), 1e-4f)
    }

    @Test
    fun cellsPerRowFollowSpan() {
        assertEquals(2, MilkGrid.perRow(4, 2))
        assertEquals(4, MilkGrid.perRow(8, 2))
        assertEquals(4, MilkGrid.perRow(12, 3))
        assertEquals(1, MilkGrid.perRow(4, 5))
        assertEquals(4, MilkGrid.perRow(4, 0))
    }

    @Test
    fun snapLineHasMinimumOfOneUnit() {
        assertEquals(4f, MilkGrid.snapLine(1f), 0f)
        assertEquals(20f, MilkGrid.snapLine(14f * 1.4f), 0f)
        assertEquals(24f, MilkGrid.snapLine(16f * 1.4f), 0f)
    }

    @Test
    fun everyDefinedLineHeightIsOnTheGrid() {
        val ty = MilkTypography(MilkFonts(FontFamily.SansSerif, FontFamily.Monospace, false))
        val styles = mapOf(
            "body" to ty.body, "title" to ty.title, "subtitle" to ty.subtitle, "caption" to ty.caption,
            "sectionTitle" to ty.sectionTitle, "labelStrong" to ty.labelStrong, "topbarTitle" to ty.topbarTitle,
            "avatarInitial" to ty.avatarInitial, "navLabel" to ty.navLabel, "dot" to ty.dot, "badge" to ty.badge,
            "noticeTitle" to ty.noticeTitle, "noticeBody" to ty.noticeBody, "posterRating" to ty.posterRating,
            "style(9f,700,9f)" to ty.style(9f, 700, 9f), "style(13f)" to ty.style(13f), "style(34f,500,40f)" to ty.style(34f, 500, 40f),
        )
        styles.forEach { (name, s) ->
            assertTrue("$name: lineHeight harus eksplisit", s.lineHeight.isSpecified)
            assertTrue("$name: lineHeight ${s.lineHeight.value} bukan kelipatan 4", MilkGrid.isOnGrid(s.lineHeight.value))
        }
    }
}

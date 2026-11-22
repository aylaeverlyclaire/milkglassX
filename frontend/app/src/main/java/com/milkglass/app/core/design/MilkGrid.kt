package com.milkglass.app.core.design

import androidx.compose.runtime.key

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object MilkGrid {

    const val UNIT = 4

    val Margin: Dp = 16.dp
    val Gutter: Dp = 16.dp

    const val COMPACT_MAX = 600f
    const val MEDIUM_MAX = 840f

    fun columnsFor(widthDp: Float): Int = when {
        widthDp < COMPACT_MAX -> 4
        widthDp < MEDIUM_MAX -> 8
        else -> 12
    }

    fun columnWidthDp(
        widthDp: Float,
        columns: Int = columnsFor(widthDp),
        marginDp: Float = Margin.value,
        gutterDp: Float = Gutter.value,
    ): Float = (widthDp - 2f * marginDp - (columns - 1) * gutterDp) / columns

    fun spanDp(
        n: Int,
        widthDp: Float,
        columns: Int = columnsFor(widthDp),
        marginDp: Float = Margin.value,
        gutterDp: Float = Gutter.value,
    ): Float = n * columnWidthDp(widthDp, columns, marginDp, gutterDp) + (n - 1) * gutterDp

    fun perRow(columns: Int, span: Int): Int = max(1, columns / max(1, span))

    fun isOnGrid(dp: Float): Boolean {
        val q = dp / UNIT
        return abs(q - q.roundToInt()) < 1e-4f
    }

    fun snap(dp: Float): Float = (dp / UNIT).roundToInt() * UNIT.toFloat()

    fun snapLine(px: Float): Float = max(UNIT.toFloat(), snap(px))
}

val Int.u: Dp get() = (this * MilkGrid.UNIT).dp

@Composable
fun MilkGridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val d = density
        val widthDp = size.width / d
        val cols = MilkGrid.columnsFor(widthDp)
        val colPx = MilkGrid.columnWidthDp(widthDp, cols) * d
        val marginPx = MilkGrid.Margin.toPx()
        val gutterPx = MilkGrid.Gutter.toPx()
        val colFill = Color(0x2EFF2D55)
        for (i in 0 until cols) {
            drawRect(colFill, Offset(marginPx + i * (colPx + gutterPx), 0f), Size(colPx, size.height))
        }
        val stepPx = MilkGrid.UNIT * d
        val minor = Color(0x182D8CFF)
        val major = Color(0x382D8CFF)
        var y = 0f
        var n = 0
        while (y <= size.height) {
            drawRect(if (n % 2 == 0) major else minor, Offset(0f, y), Size(size.width, 1f))
            y += stepPx
            n++
        }
    }
}

@Composable
fun <T> MilkColumnGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    span: Int = 2,
    rowGap: Dp = 8.dp,
    itemKey: ((T) -> Any)? = null,
    cell: @Composable (T) -> Unit,
) {
    // Kolom dihitung dari lebar kontainer sebenarnya (AppRoot membatasi 460dp), bukan lebar layar fisik;
    // kalau tidak, di landscape grid mengira ada 8-12 kolom dan poster terjepit di kontainer sempit.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = MilkGrid.columnsFor(maxWidth.value)
        val perRow = MilkGrid.perRow(cols, span)
        val rows = androidx.compose.runtime.remember(items, perRow) { items.chunked(perRow) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            rows.forEachIndexed { rowIdx, rowItems ->
                key(rowIdx) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MilkGrid.Gutter)) {
                        rowItems.forEachIndexed { colIdx, item ->
                            key(itemKey?.invoke(item) ?: colIdx) {
                                Box(Modifier.weight(1f)) { cell(item) }
                            }
                        }
                        repeat(perRow - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

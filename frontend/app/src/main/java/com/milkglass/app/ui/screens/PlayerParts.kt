package com.milkglass.app.ui.screens

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.design.MilkEasing
import com.milkglass.app.core.poster.PosterArt
import com.milkglass.app.core.poster.PosterSpec
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun BufferSpinner() {
    val t by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) value = ((withFrameNanos { it } - start) / 1e6f % 800f) / 800f
    }
    Canvas(Modifier.size(44.dp)) {
        val w = 4.dp.toPx()
        val r = (size.width - w) / 2f
        rotate(360f * t) {
            drawCircle(Color.White.copy(alpha = .3f), r, style = Stroke(w))
            drawArc(Color.White, -135f, 90f, false, topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w), style = Stroke(w))
        }
    }
}

@Composable
fun SeekBar(position: Double, max: Int, fullscreen: Boolean, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    val primary = com.milkglass.app.core.design.Milk.colors.primary
    val fill = primary
    val rest = Color.White.copy(alpha = if (fullscreen) .3f else .35f)
    Canvas(
        modifier.height(32.dp).pointerInput(max) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun upd(x: Float) {
                    val w = size.width
                    if (max <= 0) return
                    val frac = ((x - 8.dp.toPx()) / (w - 16.dp.toPx())).coerceIn(0f, 1f)
                    onSeek((frac * max).roundToInt().toDouble())
                }
                upd(down.position.x)
                drag(down.id) { upd(it.position.x); it.consume() }
            }
        },
    ) {
        val p = if (max > 0) (position / max).toFloat().coerceIn(0f, 1f) else 0f
        val cy = size.height / 2f
        val th = 4.dp.toPx()
        drawRect(rest, Offset(0f, cy - th / 2f), Size(size.width, th))
        val thumbX = 8.dp.toPx() + p * (size.width - 16.dp.toPx())
        drawRect(fill, Offset(0f, cy - th / 2f), Size(thumbX, th))
        drawCircle(fill, 8.dp.toPx(), Offset(thumbX, cy))
    }
}

private data class Burst(val xPct: Float, val yPct: Float, val color: Color, val delayS: Float)

private val BURSTS = listOf(

    Burst(.22f, .38f, Color(0xFFFFB74D), 0f), Burst(.72f, .30f, Color(0xFFFF6F91), .45f), Burst(.48f, .66f, Color(0xFFB39DDB), .9f),
)

@Composable
fun FireworksCanvas(modifier: Modifier = Modifier, iterations: Int = Int.MAX_VALUE) {
    val t by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) value = (withFrameNanos { it } - start) / 1e9f
    }
    Canvas(modifier) {
        BURSTS.forEach { b ->
            val ax = b.xPct * size.width
            val ay = b.yPct * size.height
            for (i in 0 until 14) {
                val tt = t - b.delayS
                if (tt < 0 || tt >= iterations * 1.6f) {
                    drawLine(b.color, Offset(ax, ay + 1.dp.toPx()), Offset(ax, ay + 12.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                } else {
                    val x = (tt % 1.6f) / 1.6f
                    val e = MilkEasing.EaseOut.transform(x)
                    val ty = (2f + 52f * e).dp.toPx()
                    val sy = .3f + .7f * e
                    rotate(i * 360f / 14f, Offset(ax, ay)) {
                        drawLine(b.color.copy(alpha = 1f - e), Offset(ax, ay + ty + 1.dp.toPx()), Offset(ax, ay + ty + (16.dp.toPx() - 1.dp.toPx()) * sy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}

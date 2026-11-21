package com.milkglass.app.ui.screens

import com.milkglass.app.core.design.Milk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.ui.common.MilkText

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

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


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun FullscreenOrientationEffect(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        val activity = view.context.findActivity()
        val window = activity?.window
        if (!active || activity == null || window == null) return@DisposableEffect onDispose { }
        val prevOrientation = activity.requestedOrientation
        val prevCutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = prevCutout }
            }
            activity.requestedOrientation = prevOrientation
        }
    }
}

private val SEEK_TEXT_SHADOW = Shadow(Color.Black.copy(alpha = .6f), Offset(0f, 1f), 6f)

@Immutable
internal data class SeekFx(val dir: Int, val total: Int, val token: Int)

@Composable
internal fun SeekIndicator(dir: Int, total: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "seekFx")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(720, easing = LinearEasing)), label = "phase")
    val pulse = (1f - abs(p - .5f) * 1.4f).coerceIn(.35f, 1f)
    val label = (if (dir < 0) "-" else "+") + total
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dir < 0) SeekChevron(dir, pulse)
        MilkText(label, Milk.type.style(20f, 700).copy(shadow = SEEK_TEXT_SHADOW), Color.White)
        if (dir >= 0) SeekChevron(dir, pulse)
    }
}

@Composable
private fun SeekChevron(dir: Int, alpha: Float) {
    Box(Modifier.graphicsLayer { scaleX = if (dir < 0) -1f else 1f }) {
        MilkIcon(MilkIcons.Chev, size = 24.dp, tint = Color.White.copy(alpha = alpha))
    }
}

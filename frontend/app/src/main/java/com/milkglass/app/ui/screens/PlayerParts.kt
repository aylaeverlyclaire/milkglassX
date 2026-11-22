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
import android.content.res.Configuration
import android.provider.Settings
import android.view.OrientationEventListener
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.draw.scale
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
fun SeekBar(
    positionProvider: () -> Double,
    max: Int,
    fullscreen: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Double?) -> Unit = {},
    bufferedProvider: () -> Float = { 0f },
) {
    val fill = com.milkglass.app.core.design.Milk.colors.primary
    val rest = Color.White.copy(alpha = if (fullscreen) .3f else .35f)
    // Geser = pratinjau lokal; seek ke pemutar hanya sekali saat jari diangkat (tidak membanjiri decoder).
    var scrubFrac by remember { mutableFloatStateOf(-1f) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    Canvas(
        modifier.height(32.dp).pointerInput(max) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (max <= 0) return@awaitEachGesture
                fun fracAt(x: Float): Float {
                    val inset = 8.dp.toPx()
                    return ((x - inset) / (size.width - 2f * inset)).coerceIn(0f, 1f)
                }
                var frac = fracAt(down.position.x)
                scrubFrac = frac
                currentOnScrub(frac.toDouble() * max)
                drag(down.id) {
                    frac = fracAt(it.position.x)
                    scrubFrac = frac
                    currentOnScrub(frac.toDouble() * max)
                    it.consume()
                }
                currentOnSeek((frac.toDouble() * max).roundToInt().toDouble())
                scrubFrac = -1f
                currentOnScrub(null)
            }
        },
    ) {
        val scrubbing = scrubFrac >= 0f
        val p = when {
            scrubbing -> scrubFrac
            max > 0 -> (positionProvider() / max).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }
        val cy = size.height / 2f
        val th = 4.dp.toPx()
        drawRect(rest, Offset(0f, cy - th / 2f), Size(size.width, th))
        // Segmen yang sudah ter-buffer: terlihat jelas sejauh mana bisa di-seek tanpa menunggu.
        val bufferedX = 8.dp.toPx() + bufferedProvider().coerceIn(0f, 1f) * (size.width - 16.dp.toPx())
        drawRect(Color.White.copy(alpha = .35f), Offset(0f, cy - th / 2f), Size(bufferedX, th))
        val thumbX = 8.dp.toPx() + p * (size.width - 16.dp.toPx())
        drawRect(fill, Offset(0f, cy - th / 2f), Size(thumbX, th))
        drawCircle(fill, (if (scrubbing) 12.dp else 8.dp).toPx(), Offset(thumbX, cy))
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


internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class OrientationLock { Free, Landscape, Portrait }

private const val PORTRAIT_TOLERANCE_DEG = 25
private const val LANDSCAPE_TOLERANCE_DEG = 35
private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

private fun Context.autoRotateEnabled(): Boolean =
    runCatching { Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }
        .getOrDefault(false)

/**
 * Orientasi + UI sistem pemutar:
 *  - Ponsel diputar ke landscape -> masuk fullscreen otomatis; (saat masuk pemutar dalam landscape juga).
 *  - Tombol fullscreen mengunci landscape (sensor, boleh balik 180°).
 *  - Keluar fullscreen (tombol / back / ponsel kembali tegak) mengunci portrait sementara agar tidak
 *    langsung loncat fullscreen lagi, lalu dilepas begitu ponsel benar-benar tegak.
 *  - Tablet tidak dipaksa fullscreen oleh rotasi.
 */
@Composable
fun PlayerOrientationEffect(fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isPhone = config.smallestScreenWidthDp < TABLET_MIN_SMALLEST_WIDTH_DP
    val currentFullscreen by rememberUpdatedState(fullscreen)
    val currentOnChange by rememberUpdatedState(onFullscreenChange)
    var lock by remember { mutableStateOf(OrientationLock.Free) }
    val original = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    LaunchedEffect(isLandscape, isPhone) {
        if (isPhone && isLandscape != currentFullscreen) currentOnChange(isLandscape)
    }

    LaunchedEffect(fullscreen) {
        lock = when {
            fullscreen -> OrientationLock.Landscape
            lock == OrientationLock.Landscape -> OrientationLock.Portrait
            else -> lock
        }
    }

    LaunchedEffect(lock, activity) {
        val a = activity ?: return@LaunchedEffect
        val wanted = when (lock) {
            OrientationLock.Free -> original
            OrientationLock.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationLock.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (a.requestedOrientation != wanted) a.requestedOrientation = wanted
    }

    DisposableEffect(activity) {
        onDispose { if (activity != null) activity.requestedOrientation = original }
    }

    DisposableEffect(activity, lock) {
        val a = activity
        val mode = lock
        if (a == null || mode == OrientationLock.Free) return@DisposableEffect onDispose { }
        var sawLandscape = false
        val listener = object : OrientationEventListener(a) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val portrait = degrees <= PORTRAIT_TOLERANCE_DEG || degrees >= 360 - PORTRAIT_TOLERANCE_DEG
                val landscape = abs(degrees - 90) <= LANDSCAPE_TOLERANCE_DEG || abs(degrees - 270) <= LANDSCAPE_TOLERANCE_DEG
                when (mode) {
                    OrientationLock.Landscape -> when {
                        landscape -> sawLandscape = true
                        portrait && sawLandscape && a.autoRotateEnabled() -> {
                            sawLandscape = false
                            currentOnChange(false)
                        }
                    }
                    OrientationLock.Portrait -> if (portrait) lock = OrientationLock.Free
                    OrientationLock.Free -> Unit
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    DisposableEffect(fullscreen) {
        val window = activity?.window
        if (!fullscreen || activity == null || window == null) return@DisposableEffect onDispose { }
        val prevCutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
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
        }
    }
}

/** Layar tetap menyala selama video diputar / buffering; dilepas saat jeda supaya hemat baterai. */
@Composable
fun KeepScreenOnEffect(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

private val SEEK_TEXT_SHADOW = Shadow(Color.Black.copy(alpha = .6f), Offset(0f, 1f), 6f)

@Immutable
internal data class SeekFx(val dir: Int, val total: Int, val token: Int)

@Composable
internal fun SeekIndicator(dir: Int, total: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "seekFx")
    val pState = t.animateFloat(0f, 1f, infiniteRepeatable(tween(720, easing = LinearEasing)), label = "phase")
    val pulseProvider: () -> Float = remember(pState) { { (1f - abs(pState.value - .5f) * 1.4f).coerceIn(.35f, 1f) } }
    val label = (if (dir < 0) "-" else "+") + total
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dir < 0) SeekChevron(dir, pulseProvider)
        MilkText(label, Milk.type.style(20f, 700).copy(shadow = SEEK_TEXT_SHADOW), Color.White)
        if (dir >= 0) SeekChevron(dir, pulseProvider)
    }
}

@Composable
private fun SeekChevron(dir: Int, alphaProvider: () -> Float) {
    Box(
        Modifier
            .scale(scaleX = if (dir < 0) -1f else 1f, scaleY = 1f)
            .graphicsLayer { alpha = alphaProvider() }
    ) {
        MilkIcon(MilkIcons.Chev, size = 24.dp, tint = Color.White)
    }
}

package com.milkglass.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.milkClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressDim: Boolean = true,
    guard: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val dim = pressDim && pressed
    val guarded = rememberClickGuard(onClick, guard)
    this
        .then(
            if (dim) Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = .08f), blendMode = BlendMode.SrcAtop)
                }
            else Modifier,
        )
        .clickable(interactionSource = src, indication = null, enabled = enabled, role = role, onClick = guarded)
}

private const val CLICK_GUARD_MS = 180L

@Composable
private fun rememberClickGuard(onClick: () -> Unit, guard: Boolean = true): () -> Unit {
    val last = remember { LongArray(1) }
    val latest = androidx.compose.runtime.rememberUpdatedState(onClick)
    val latestGuard = androidx.compose.runtime.rememberUpdatedState(guard)
    return remember {
        {
            val now = SystemClock.uptimeMillis()
            if (!latestGuard.value || now - last[0] >= CLICK_GUARD_MS) { last[0] = now; latest.value() }
        }
    }
}

private val GrayscaleFilter: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
private val LockedThumbFilter: ColorFilter by lazy {
    val cm = ColorMatrix().apply {
        setToSaturation(.3f)
        val b = ColorMatrix(floatArrayOf(.7f, 0f, 0f, 0f, 0f, 0f, .7f, 0f, 0f, 0f, 0f, 0f, .7f, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
        timesAssign(b)
    }
    ColorFilter.colorMatrix(cm)
}

fun Modifier.featureOffLook(): Modifier = drawWithCache {
    val paint = Paint().apply {
        alpha = .38f
        colorFilter = GrayscaleFilter
    }
    onDrawWithContent {
        drawIntoCanvas { c ->
            c.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            c.restore()
        }
    }
}.pointerInput(Unit) { detectTapGestures { } }

fun Modifier.dimmedLook(): Modifier = graphicsLayer { alpha = .38f }.pointerInput(Unit) { detectTapGestures { } }

fun Modifier.lockedThumbLook(): Modifier = drawWithCache {
    val paint = Paint().apply { colorFilter = LockedThumbFilter }
    onDrawWithContent {
        drawIntoCanvas { c ->
            c.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            c.restore()
        }
    }
}

fun Modifier.bleed(h: Dp): Modifier = layout { measurable, constraints ->
    val px = h.roundToPx()
    val p = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px),
    )
    layout(p.width - 2 * px, p.height) { p.place(-px, 0) }
}

val Gutter: Dp = com.milkglass.app.core.design.MilkGrid.Margin

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.milkCombinedClickable(
    pressDim: Boolean = true,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val dim = pressDim && pressed
    val guarded = rememberClickGuard(onClick)
    this
        .then(
            if (dim) Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = .08f), blendMode = BlendMode.SrcAtop)
                }
            else Modifier,
        )
        .combinedClickable(interactionSource = src, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = guarded)
}

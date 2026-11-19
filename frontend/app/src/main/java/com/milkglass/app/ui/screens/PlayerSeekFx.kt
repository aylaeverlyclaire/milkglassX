package com.milkglass.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.ui.common.MilkText
import kotlin.math.abs

internal data class SeekFx(val dir: Int, val total: Int, val token: Int)

@Composable
internal fun SeekIndicator(dir: Int, total: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "seekFx")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(720, easing = LinearEasing)), label = "phase")
    val pulse = (1f - abs(p - .5f) * 1.4f).coerceIn(.35f, 1f)
    val label = (if (dir < 0) "-" else "+") + total
    val shadow = Shadow(Color.Black.copy(alpha = .6f), Offset(0f, 1f), 6f)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {

        if (dir < 0) SeekChevron(dir, pulse)
        MilkText(label, Milk.type.style(20f, 700).copy(shadow = shadow), Color.White)
        if (dir >= 0) SeekChevron(dir, pulse)
    }
}

@Composable
private fun SeekChevron(dir: Int, alpha: Float) {
    Box(Modifier.graphicsLayer { scaleX = if (dir < 0) -1f else 1f }) {
        MilkIcon(MilkIcons.Chev, size = 24.dp, tint = Color.White.copy(alpha = alpha))
    }
}

package com.milkglass.app.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.milkglass.app.core.model.Anime

fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

fun ensureContrast(fg: Color, bg: Color, min: Double = 3.0): Color {
    if (contrastRatio(fg, bg) >= min) return fg
    val target = if (bg.luminance() < 0.5f) Color.White else Color.Black
    var t = 0f
    var out = fg
    while (t < 1f && contrastRatio(out, bg) < min) {
        t = minOf(1f, t + 0.05f)
        out = lerp(fg, target, t)
    }
    return out
}

fun accentFor(a: Anime, bg: Color): Color {
    val from = parseCssColor(a.colorFrom) ?: Color.Gray
    val to = parseCssColor(a.colorTo) ?: Color.Gray
    val base = if (contrastRatio(from, bg) >= contrastRatio(to, bg)) from else to
    return ensureContrast(base, bg)
}

package com.milkglass.app.core.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.milkglass.app.core.model.Anime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

val Number.cssDp: Dp get() = toFloat().dp
val Number.cssSp: TextUnit get() = toFloat().sp

fun parseCssColor(value: String): Color? {
    val v = value.trim()
    if (!v.startsWith("#")) return null
    val hex = v.substring(1)
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val r = full.substring(0, 2).toIntOrNull(16) ?: return null
    val g = full.substring(2, 4).toIntOrNull(16) ?: return null
    val b = full.substring(4, 6).toIntOrNull(16) ?: return null
    val a = if (full.length == 8) full.substring(6, 8).toIntOrNull(16) ?: return null else 255
    return Color(r, g, b, a)
}

fun cssLinearGradient(angleDeg: Float, colors: List<Color>, stops: List<Float>? = null): Brush =
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val a = Math.toRadians(angleDeg.toDouble())
            val dx = sin(a).toFloat()
            val dy = (-cos(a)).toFloat()
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            return LinearGradientShader(
                from = Offset(cx - dx * half, cy - dy * half),
                to = Offset(cx + dx * half, cy + dy * half),
                colors = colors,
                colorStops = stops,
                tileMode = TileMode.Clamp,
            )
        }
    }

fun cssLinearGradientTo(direction: CssDirection, colors: List<Color>, stops: List<Float>? = null): Brush =
    cssLinearGradient(direction.degrees, colors, stops)

enum class CssDirection(val degrees: Float) { Top(0f), Right(90f), Bottom(180f), Left(270f) }

val CssTransparent = Color(0f, 0f, 0f, 0f)

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

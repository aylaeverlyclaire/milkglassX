package com.milkglass.app.core.design

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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

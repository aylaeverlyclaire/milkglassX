package com.milkglass.app.core.design

import androidx.compose.runtime.Immutable

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val LAYERS = 8

@Immutable
data class CssShadow(
    val dx: Dp,
    val dy: Dp,
    val blur: Dp,
    val color: Color,
    val spread: Dp = 0.dp,
)

fun Modifier.cssShadow(vararg shadows: CssShadow, shape: Shape = RectangleShape): Modifier =
    drawBehind {

        val own = shape.createOutline(size, layoutDirection, this)
        val ownPath = Path().apply { addOutline(own) }
        shadows.reversed().forEach { s ->
            val spread = s.spread.toPx()
            val grown = Size(size.width + 2 * spread, size.height + 2 * spread)
            val outline = shape.createOutline(grown, layoutDirection, this)
            val sigma = s.blur.toPx() / 2f
            val hardwareBlur = Build.VERSION.SDK_INT >= 28 || sigma <= 0.5f
            val paint = Paint().apply {
                color = s.color
                isAntiAlias = true
                if (hardwareBlur && sigma > 0.5f) {
                    val radius = (sigma - 0.5f) / 0.57735f
                    asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                }
            }
            if (!hardwareBlur) {

                val a0 = s.color.alpha.coerceIn(0f, 1f)
                val layerAlpha = 1f - Math.pow((1f - a0).toDouble(), 1.0 / LAYERS).toFloat()
                val layerPaint = Paint().apply { color = s.color.copy(alpha = layerAlpha); isAntiAlias = true }
                clipPath(ownPath, ClipOp.Difference) {
                    drawIntoCanvas { canvas ->
                        for (k in 0 until LAYERS) {
                            val g = spread + sigma * 3f * (k + 0.5f) / LAYERS - sigma * 1.5f
                            val w = size.width + 2 * g
                            val h = size.height + 2 * g
                            if (w <= 0f || h <= 0f) continue
                            val layerOutline = shape.createOutline(Size(w, h), layoutDirection, this@drawBehind)
                            canvas.save()
                            canvas.translate(s.dx.toPx() - g, s.dy.toPx() - g)
                            canvas.drawOutline(layerOutline, layerPaint)
                            canvas.restore()
                        }
                    }
                }
                return@forEach
            }
            clipPath(ownPath, ClipOp.Difference) {
                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.translate(s.dx.toPx() - spread, s.dy.toPx() - spread)
                    canvas.drawOutline(outline, paint)
                    canvas.restore()
                }
            }
        }
    }

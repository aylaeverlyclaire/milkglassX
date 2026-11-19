package com.milkglass.app.core.design

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit

@Immutable
class MilkFonts(val sans: FontFamily, val mono: FontFamily, val robotoMissing: Boolean)

@OptIn(ExperimentalTextApi::class)
fun loadMilkFonts(ctx: Context): MilkFonts {
    fun family(resName: String, weights: List<Int>): FontFamily? {
        val id = ctx.resources.getIdentifier(resName, "font", ctx.packageName)
        if (id == 0) return null
        return FontFamily(
            weights.map { w ->
                Font(
                    resId = id,
                    weight = FontWeight(w),
                    style = FontStyle.Normal,
                    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                )
            },
        )
    }
    val sans = family("roboto_variable", listOf(400, 500, 600, 700))
    val mono = family("roboto_mono_variable", listOf(400, 500))
    return MilkFonts(
        sans = sans ?: FontFamily.SansSerif,
        mono = mono ?: FontFamily.Monospace,
        robotoMissing = sans == null || mono == null,
    )
}

@Composable
fun rememberMilkFonts(): MilkFonts {
    val ctx = LocalContext.current
    return remember(ctx) { loadMilkFonts(ctx) }
}

@Immutable
class MilkTypography(private val fonts: MilkFonts) {

    fun style(
        sizePx: Float,
        weight: Int = 400,
        lineHeightPx: Float? = INHERIT_1_4,
        letterSpacingPx: Float = 0f,
        mono: Boolean = false,
    ): TextStyle {
        val lh: TextUnit = when (lineHeightPx) {
            null -> TextUnit.Unspecified
            INHERIT_1_4 -> MilkGrid.snapLine(sizePx * 1.4f).cssSp
            else -> MilkGrid.snapLine(lineHeightPx).cssSp
        }
        return TextStyle(
            fontFamily = if (mono) fonts.mono else fonts.sans,
            fontSize = sizePx.cssSp,
            fontWeight = FontWeight(weight),
            lineHeight = lh,
            letterSpacing = letterSpacingPx.cssSp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }

    val body get() = style(14f, 400, 20f)
    val title get() = style(20f, 600, 26f)
    val subtitle get() = style(16f, 600, 22f, .1f)
    val caption get() = style(12f, 400, 16f, .2f)
    val sectionTitle get() = style(18f, 600, 24f)
    val labelStrong get() = style(13f, 600)
    val topbarTitle get() = style(22f, 400, 28f)
    val avatarInitial get() = style(15f, 600)
    val navLabel get() = style(12f, 500, INHERIT_1_4, .3f)
    val dot get() = style(12f, 600, 18f)
    val button get() = style(14f, 600, null, .1f)
    val buttonWide get() = style(15f, 600, null, .1f)
    val buttonSmall get() = style(13f, 600, null, .1f)
    val chip get() = style(14f, 500, null)
    val badge get() = style(12f, 600, 20f, .3f)
    val noticeTitle get() = style(14f, 600, 20f)
    val noticeBody get() = style(13f, 400, 18f)
    val posterRating get() = style(12f, 600, 16f)
    val heroTitle get() = style(24f, 600, 30f)
    val heroMeta get() = style(13f)
    val settingTitle get() = style(16f, 400, 24f, .3f)
    val settingSubtitle get() = style(14f, 400, 20f)
    val tab get() = style(14f, 500)
    val searchInput get() = style(16f)
    val dayLabel get() = style(12f, 400, INHERIT_1_4, .3f)
    val dayNumber get() = style(20f, 500, 24f)
    val countdown get() = style(13f, 600)
    val timelineTime get() = style(12f, 500)
    val episodeThumb get() = style(14f, 600)
    val toast get() = style(14f, 500, 20f, .1f)
    val skipButton get() = style(13f, 600, null)
    val subtitleLine get() = style(14f, 500, 18f)
    val chatLine get() = style(14f, 600, 20f)
    val chatTime get() = style(12f, 400)

    companion object {
        const val INHERIT_1_4 = -1f
    }
}

val LocalMilkTypography = staticCompositionLocalOf<MilkTypography> {
    error("MilkTypography belum disediakan. Bungkus UI dengan MilkTheme { }.")
}

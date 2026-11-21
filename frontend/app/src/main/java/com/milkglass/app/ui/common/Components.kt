package com.milkglass.app.ui.common

import androidx.compose.runtime.remember

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.design.CssTransparent
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.cssLinearGradient
import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.poster.PosterArt
import com.milkglass.app.core.poster.PosterSpec

@Composable
fun MilkText(
    text: String,
    style: TextStyle,
    color: Color = Milk.colors.onSurface,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    ellipsize: Boolean = false,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: androidx.compose.ui.text.style.TextAlign.Unspecified),
        maxLines = maxLines,
        overflow = if (ellipsize) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

@Composable
fun Clamp1(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) =
    MilkText(text, style, color, modifier, maxLines = 1, ellipsize = true)

fun gradientOf(a: Anime, angle: Float = 150f) =
    cssLinearGradient(angle, listOf(parseCssColor(a.colorFrom) ?: Color.Black, parseCssColor(a.colorTo) ?: Color.Black))

enum class ButtonVariant { Tonal, Primary, Outlined, Error, Gold }

@Composable
fun MilkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    variant: ButtonVariant = ButtonVariant.Tonal,
    wide: Boolean = false,
    small: Boolean = false,
    onHero: Boolean = false,
    dimmed: Boolean = false,
    featureOff: Boolean = false,
) {
    val c = Milk.colors
    val (bg, fg) = when {
        onHero && variant == ButtonVariant.Primary -> Color.White to Color(0xFF1D1B20)
        onHero -> Color.White.copy(alpha = .22f) to Color.White
        variant == ButtonVariant.Primary -> c.primary to c.onPrimary
        variant == ButtonVariant.Outlined -> CssTransparent to c.primary
        variant == ButtonVariant.Error -> c.errorContainer to c.onErrorContainer
        variant == ButtonVariant.Gold -> c.premiumContainer to c.onPremC
        else -> c.secondaryContainer to c.onSecondaryContainer
    }
    val style = when {
        wide -> Milk.type.buttonWide
        small -> Milk.type.buttonSmall
        else -> Milk.type.button
    }
    val h = if (wide) 48.dp else if (small) 36.dp else 40.dp
    val padH = if (small) 12.dp else 20.dp
    var m = modifier
        .height(h)
        .then(if (wide) Modifier.fillMaxWidth() else Modifier)
        .clip(RoundedCornerShape(999.dp))
        .background(bg)
        .then(if (variant == ButtonVariant.Outlined && !onHero) Modifier.border(1.dp, c.outline, RoundedCornerShape(999.dp)) else Modifier)
        .milkClickable(enabled = !dimmed && !featureOff, onClick = onClick)
        .padding(horizontal = padH)
    if (dimmed) m = m.dimmedLook()
    if (featureOff) m = m.featureOffLook()
    Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) MilkIcon(icon, size = 20.dp, tint = fg)
        MilkText(text, style, fg, maxLines = 1)
    }
}

@Composable
fun MilkIconButton(
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = Milk.colors.onSurfaceVariant,
    featureOff: Boolean = false,
) {
    var m = modifier.size(size).clip(CircleShape).milkClickable(enabled = !featureOff, onClick = onClick)
    if (featureOff) m = m.featureOffLook()
    Box(m, contentAlignment = Alignment.Center) { MilkIcon(icon, tint = tint) }
}

@Composable
fun AvatarButton(initial: String, onClick: () -> Unit) {
    val c = Milk.colors
    Box(
        Modifier.padding(8.dp).size(36.dp).clip(CircleShape).background(c.primary).milkClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MilkText(initial, Milk.type.avatarInitial, c.onPrimary) }
}

@Composable
fun MilkChip(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Milk.colors
    val shape = RoundedCornerShape(8.dp)
    val fg = if (active) c.onSecondaryContainer else c.onSurfaceVariant
    Row(
        modifier.height(32.dp).clip(shape)
            .then(if (active) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outline, shape))
            .milkClickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) MilkIcon(MilkIcons.Check, size = 20.dp, tint = fg)
        MilkText(text, Milk.type.chip, fg, maxLines = 1, ellipsize = true)
    }
}

private val RatingYellow = Color(0xFFFFD54F)

enum class BadgeKind { Default, Success, Gold, Neutral, Early, NeutralOnArt, RatingOnArt }

@Composable
fun MilkBadge(text: String, kind: BadgeKind = BadgeKind.Default, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, icon: Int? = null) {
    val c = Milk.colors
    val (bg, fg) = when (kind) {
        BadgeKind.Default -> c.secondaryContainer to c.onSecondaryContainer
        BadgeKind.Success -> c.primaryContainer to c.onPrimaryContainer
        BadgeKind.Gold -> c.premiumContainer to c.onPremC
        BadgeKind.Neutral -> c.scHighest to c.onSurfaceVariant
        BadgeKind.Early -> c.tertiaryContainer to c.onTertiaryContainer
        BadgeKind.NeutralOnArt -> Color.Black.copy(alpha = .4f) to Color.White
        BadgeKind.RatingOnArt -> Color.Black.copy(alpha = .4f) to RatingYellow
    }
    Box(
        modifier.height(20.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (onClick != null) Modifier.milkClickable(pressDim = false, onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) MilkIcon(icon, size = 12.dp, tint = fg)
            MilkText(text, Milk.type.badge, fg, maxLines = 1)
        }
    }
}

@Composable
fun LabelStrong(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MilkText(
        text, Milk.type.labelStrong, Milk.colors.primary,
        modifier.clip(RoundedCornerShape(8.dp)).milkClickable(pressDim = false, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, right: @Composable (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MilkText(title, Milk.type.sectionTitle, Milk.colors.onSurface, Modifier.weight(1f, fill = false))
        if (right != null) {
            Spacer(Modifier.width(8.dp))

            Box(Modifier.offset(x = 4.dp)) { right() }
        }
    }
}

@Composable
fun MilkCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Milk.colors.surfaceContainerLow).border(1.dp, Milk.colors.outlineVariant, shape), content = content)
}

@Composable
fun MilkDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Milk.colors.outlineVariant.copy(alpha = Milk.colors.outlineVariant.alpha * .6f)))
}

@Composable
fun EmptyState(modifier: Modifier = Modifier, topPadding: Dp = 36.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = topPadding, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun TopBar(title: String, bare: Boolean, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null, right: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(64.dp)
            .padding(start = if (bare) 4.dp else Gutter, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        MilkText(title, Milk.type.topbarTitle, Milk.colors.onSurface, Modifier.weight(1f), maxLines = 1, ellipsize = true)
        right()
    }
}

@Composable
fun PosterView(spec: PosterSpec, anime: Anime, ratingText: String, modifier: Modifier = Modifier, small: Boolean = false, progressPct: Float? = null) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.aspectRatio(3f / 4f).clip(shape).background(gradientOf(anime))) {
        PosterArt(spec, Modifier.fillMaxSize())
        if (!small) {
            Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                MilkText(
                    "★ $ratingText", Milk.type.posterRating, Color.White,
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .4f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        if (progressPct != null) ProgressBar(progressPct, 4.dp, Color.Black.copy(alpha = .45f), Modifier.align(Alignment.BottomStart))
    }
}

@Composable
fun ProgressBar(pct: Float, height: Dp, track: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth((pct.coerceIn(0f, 100f)) / 100f).background(Color.White))
    }
}

@Composable
fun ArtBackdrop(spec: PosterSpec, anime: Anime, shadeAlpha: Float, shadeStop: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val base = (shadeAlpha * 1.5f).coerceAtMost(.82f)
    val reach = (shadeStop + .2f).coerceAtMost(1f)
    val baseBrush = remember(anime.colorFrom, anime.colorTo) { gradientOf(anime) }
    val shadeBrush = remember(base, reach) {
        cssLinearGradient(
            0f,
            listOf(
                Color.Black.copy(alpha = base),
                Color.Black.copy(alpha = base * .72f),
                Color.Black.copy(alpha = base * .36f),
                Color.Black.copy(alpha = base * .12f),
                CssTransparent,
            ),
            listOf(0f, reach * .4f, reach * .7f, reach * .88f, reach),
        )
    }
    Box(modifier.background(baseBrush)) {
        PosterArt(spec, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(shadeBrush))
        content()
    }
}

@Composable
fun ChevronIcon() = MilkIcon(MilkIcons.Chev, size = 24.dp, tint = Milk.colors.onSurfaceVariant)

@Composable
fun SurfaceFill(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxSize().background(Milk.colors.surface), content = content)

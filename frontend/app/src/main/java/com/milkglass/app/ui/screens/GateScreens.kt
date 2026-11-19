package com.milkglass.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkEasing
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.ui.common.Gutter
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.milkClickable
import kotlinx.coroutines.launch

private val SplashBg = Color(0xFF141218)
private val SplashFg = Color(0xFFE6E0E9)
private val SplashAccent = Color(0xFFD0BCFF)
private val SplashTrack = Color(0xFF49454F)

@Composable
fun SplashScreen() {
    val ty = Milk.type
    val logo = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    LaunchedEffect(Unit) { logo.animateTo(1f, tween(900, easing = MilkEasing.Decelerate)) }
    LaunchedEffect(Unit) { word.animateTo(1f, tween(600, delayMillis = 350, easing = MilkEasing.EaseOut)) }
    val inf = rememberInfiniteTransition(label = "shimmer")
    val shimmer by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = MilkEasing.EaseInOut), RepeatMode.Reverse), label = "s",
    )
    Column(
        Modifier.fillMaxSize().background(SplashBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.graphicsLayer {
                val p = logo.value
                val s = .3f + (1f - .3f) * p
                scaleX = s; scaleY = s
                rotationZ = -30f * (1f - p)
                alpha = p
            },
        ) { MilkIcon(MilkIcons.BrandLogo, size = 88.dp, tint = SplashAccent) }
        MilkText(
            "Milkglass", ty.style(34f, 500, 40f, .5f), SplashFg,
            Modifier.graphicsLayer { alpha = word.value; translationY = 8.dp.toPx() * (1f - word.value) },
        )
        Box(Modifier.padding(top = 20.dp).width(96.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SplashTrack)) {

            Box(
                Modifier.width(40.dp).height(4.dp).graphicsLayer {
                    translationX = size.width * (-1f + 2.5f * shimmer)
                }.background(SplashAccent),
            )
        }
    }
}

@Composable
fun LoginScreen(ctl: AppController) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(c.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Gutter)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 24.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            MilkIcon(MilkIcons.BrandLogo, size = 56.dp, tint = c.primary)
            MilkText("Milkglass", ty.style(34f, 500, 40f, .5f), c.onSurface)
            MilkText(tr.t("auth.tagline"), ty.body, c.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Column {
            val busy = ctl.isSigningIn
            Row(
                Modifier.fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(999.dp)).background(c.surface)
                    .border(1.dp, c.outline, RoundedCornerShape(999.dp))
                    .graphicsLayer { alpha = if (busy) .6f else 1f }
                    .milkClickable(enabled = !busy) { scope.launch { ctl.signInWithGoogle() } }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MilkIcon(MilkIcons.GoogleG, size = 20.dp, tint = Color.Unspecified)
                MilkText(tr.t("auth.continueWithGoogle"), ty.style(16f, 600, null), c.onSurface)
            }
            val link = SpanStyle(color = c.onSurface, textDecoration = TextDecoration.Underline)
            val text = buildAnnotatedString {
                append(tr.t("auth.consentPrefix") + " ")
                withLink(LinkAnnotation.Clickable("terms", TextLinkStyles(link)) { ctl.openLegalDocument("terms") }) { append(tr.t("auth.termsOfService")) }
                append(" " + tr.t("common.and") + " ")
                withLink(LinkAnnotation.Clickable("usage", TextLinkStyles(link)) { ctl.openLegalDocument("usage") }) { append(tr.t("auth.usagePolicy")) }
                append(" " + tr.t("auth.consentMiddle") + " ")
                withLink(LinkAnnotation.Clickable("privacy", TextLinkStyles(link)) { ctl.openLegalDocument("privacy") }) { append(tr.t("legal.privacyLink")) }
                append(tr.t("auth.consentSuffix"))
            }
            BasicText(
                text,
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 16.dp),
                style = ty.style(12f, 400, 18f).copy(color = c.onSurfaceVariant, textAlign = TextAlign.Center),
            )
        }
    }
}

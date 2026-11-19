package com.milkglass.app.core.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

object MilkEasing {

    val Decelerate: Easing = CubicBezierEasing(.2f, .8f, .2f, 1f)

    val EaseOut: Easing = CubicBezierEasing(0f, 0f, .58f, 1f)

    val Ease: Easing = CubicBezierEasing(.25f, .1f, .25f, 1f)

    val Standard: Easing = CubicBezierEasing(.4f, 0f, .2f, 1f)

    val EaseInOut: Easing = CubicBezierEasing(.42f, 0f, .58f, 1f)
    val Linear: Easing = LinearEasing
}

data class MilkAnim(
    val name: String,
    val durationMs: Int,
    val easing: Easing,
    val delayMs: Int = 0,
    val infinite: Boolean = false,
    val alternate: Boolean = false,
)

object MilkMotion {
    val toastIn = MilkAnim("toast-in", 180, MilkEasing.EaseOut)
    val sheetUp = MilkAnim("sheet-up", 220, MilkEasing.EaseOut)
    val logoPop = MilkAnim("logo-pop", 900, MilkEasing.Decelerate)
    val fadeUpSmall = MilkAnim("fade-up-small", 600, MilkEasing.EaseOut, delayMs = 350)
    val shimmerSlide = MilkAnim("shimmer-slide", 1100, MilkEasing.EaseInOut, infinite = true, alternate = true)
    val kenBurns = MilkAnim("ken-burns", 26000, MilkEasing.EaseInOut, infinite = true, alternate = true)
    val toastCardFade = MilkAnim("toast-card-fade", 5000, MilkEasing.Ease)
    val fireworkBurst = MilkAnim("firework-burst", 1600, MilkEasing.EaseOut, infinite = true)
    val paneEnter = MilkAnim("pane-enter", 300, MilkEasing.Decelerate)
    val paneBack = MilkAnim("pane-back", 260, MilkEasing.Decelerate)
    val screenFadeIn = MilkAnim("screen-fade-in", 340, MilkEasing.Decelerate)
    val slideIn = MilkAnim("slide-in", 240, MilkEasing.Decelerate)
    val slideOut = MilkAnim("slide-out", 220, MilkEasing.EaseOut)

    val viewExit = MilkAnim("view-exit", 220, MilkEasing.Standard)
    val spin = MilkAnim("spin", 800, MilkEasing.Linear, infinite = true)

    const val TRANSITION_OPACITY = 200
    const val TRANSITION_COLOR = 200
    const val TRANSITION_TRANSFORM = 200
    const val TRANSITION_ALL = 150
}

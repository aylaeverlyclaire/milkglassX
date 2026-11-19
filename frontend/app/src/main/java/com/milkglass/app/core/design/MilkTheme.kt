package com.milkglass.app.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

enum class ThemeMode(val key: String) {
    System("system"), Light("light"), Dark("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: System
    }
}

val LocalMilkDark = staticCompositionLocalOf { false }
val LocalMilkFonts = staticCompositionLocalOf<MilkFonts> {
    error("MilkFonts belum disediakan. Bungkus UI dengan MilkTheme { }.")
}

@Composable
fun MilkTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val fonts = rememberMilkFonts()
    val typography = remember(fonts) { MilkTypography(fonts) }
    CompositionLocalProvider(
        LocalMilkColors provides (if (dark) MilkDarkColors else MilkLightColors),
        LocalMilkTypography provides typography,
        LocalMilkFonts provides fonts,
        LocalMilkDark provides dark,
        content = content,
    )
}

object Milk {
    val colors: MilkColors @Composable @ReadOnlyComposable get() = LocalMilkColors.current
    val type: MilkTypography @Composable @ReadOnlyComposable get() = LocalMilkTypography.current
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalMilkDark.current
}

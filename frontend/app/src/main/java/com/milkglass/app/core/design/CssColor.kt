package com.milkglass.app.core.design

import androidx.compose.ui.graphics.Color

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

package com.milkglass.app.ui.screens

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.design.Milk
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.milkClickable
import kotlin.math.roundToInt

/** Kecepatan yang ditawarkan di picker. 1x selalu ada supaya bisa kembali normal. */
internal val PLAYER_SPEEDS = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)

/** Kecepatan sementara saat layar ditahan (long-press). */
internal const val HOLD_BOOST_SPEED = 2f

/** Lebar penuh layar digeser = sekian detik (swipe horizontal untuk seek). */
internal const val SEEK_SWIPE_SPAN_SEC = 120.0

/** 1 -> "1", 1.25 -> "1.25", 0.5 -> "0.5". */
internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')

@Immutable
internal data class GestureHud(val title: String, val detail: String? = null)

@Composable
internal fun GestureHudPill(hud: GestureHud, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .6f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MilkText(hud.title, Milk.type.style(16f, 700), Color.White)
        val detail = hud.detail
        if (detail != null) MilkText(detail, Milk.type.style(12f, 500), Color.White.copy(alpha = .85f))
    }
}

@Composable
internal fun PlayerChip(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(32.dp).widthIn(min = 40.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White.copy(alpha = .92f) else Color.White.copy(alpha = .18f))
            .milkClickable(enabled = enabled, pressDim = false, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        MilkText(label, Milk.type.style(12f, 600), if (selected) Color(0xFF1D1B20) else Color.White)
    }
}

@Composable
internal fun SpeedPicker(current: Float, onPick: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .72f))
            // Sela antar chip tidak boleh meneruskan tap ke video (akan menyembunyikan kontrol).
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PLAYER_SPEEDS.forEach { s ->
            key(s) { PlayerChip(formatSpeed(s) + "×", selected = s == current) { onPick(s) } }
        }
    }
}

/** Kecerahan jendela & volume media untuk gestur swipe vertikal (nilai 0f..1f). */
internal class SystemLevels(private val activity: Activity?, private val audio: AudioManager?) {

    fun brightness(): Float {
        val set = activity?.window?.attributes?.screenBrightness ?: -1f
        if (set >= 0f) return set
        val resolver = activity?.contentResolver ?: return .5f
        val sys = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(.5f)
        return sys.coerceIn(0f, 1f)
    }

    fun setBrightness(level: Float) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.also { it.screenBrightness = level.coerceIn(.02f, 1f) }
    }

    fun restoreBrightness() {
        val window = activity?.window ?: return
        window.attributes = window.attributes.also {
            it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun maxVolume(): Int = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)

    fun volume(): Float {
        val a = audio ?: return 0f
        return (a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume()).coerceIn(0f, 1f)
    }

    fun setVolume(level: Float) {
        val a = audio ?: return
        val idx = (level.coerceIn(0f, 1f) * maxVolume()).roundToInt().coerceIn(0, maxVolume())
        runCatching { a.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
    }
}

/** Dibuat di level layar pemutar (bukan VideoArea) agar kecerahan tidak ter-reset saat fullscreen berganti. */
@Composable
internal fun rememberSystemLevels(): SystemLevels {
    val view = LocalView.current
    val ctx = LocalContext.current
    val levels = remember(view) {
        SystemLevels(view.context.findActivity(), ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
    }
    DisposableEffect(levels) { onDispose { levels.restoreBrightness() } }
    return levels
}

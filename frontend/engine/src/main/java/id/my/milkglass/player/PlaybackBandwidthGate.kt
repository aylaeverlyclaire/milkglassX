package id.my.milkglass.player

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Gerbang bandwidth lintas-subsistem: menjeda prefetch aset non-kritis (poster/thumbnail)
 * selama video sedang buffering awal maupun re-buffering, plus masa tenang singkat
 * agar koneksi ExoPlayer tidak diperebutkan lonjakan HTTP paralel.
 */
object PlaybackBandwidthGate {
    private const val POST_BUFFER_COOLDOWN_MS = 1_500L

    private val _isVideoBuffering = MutableStateFlow(false)
    val isVideoBuffering: StateFlow<Boolean> = _isVideoBuffering.asStateFlow()

    private val cooldownUntilUptimeMs = AtomicLong(0L)

    fun onBufferingChanged(buffering: Boolean) {
        val prev = _isVideoBuffering.getAndSet(buffering)
        if (prev && !buffering) {
            cooldownUntilUptimeMs.set(SystemClock.uptimeMillis() + POST_BUFFER_COOLDOWN_MS)
        }
    }

    fun isThrottled(): Boolean =
        _isVideoBuffering.value || SystemClock.uptimeMillis() < cooldownUntilUptimeMs.get()

    suspend fun awaitClear(maxWaitMs: Long = 8_000L): Boolean {
        if (_isVideoBuffering.value) {
            val cleared = withTimeoutOrNull(maxWaitMs) {
                _isVideoBuffering.first { !it }
                true
            } ?: false
            if (!cleared) return false
        }
        val remainingCooldown = cooldownUntilUptimeMs.get() - SystemClock.uptimeMillis()
        if (remainingCooldown > 0L) {
            delay(remainingCooldown)
        }
        return !_isVideoBuffering.value
    }
}

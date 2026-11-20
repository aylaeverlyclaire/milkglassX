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
 * Gerbang bandwidth lintas-subsistem: menjeda prefetch aset non-kritis (poster/thumbnail/download/prebuffer)
 * selama video sedang buffering awal maupun re-buffering, plus masa tenang singkat
 * agar koneksi ExoPlayer tidak diperebutkan lonjakan HTTP paralel.
 */
object PlaybackBandwidthGate {
    private const val POST_BUFFER_COOLDOWN_MS = 1_500L

    private val _isVideoBuffering = MutableStateFlow(false)
    val isVideoBuffering: StateFlow<Boolean> = _isVideoBuffering.asStateFlow()

    private val cooldownUntilUptimeMs = AtomicLong(0L)

    fun onBufferingChanged(buffering: Boolean) {
        if (!buffering && _isVideoBuffering.value) {
            // Set cooldown BEFORE clearing _isVideoBuffering so waiters waking on !it never bypass the cooldown.
            cooldownUntilUptimeMs.set(SystemClock.uptimeMillis() + POST_BUFFER_COOLDOWN_MS)
        }
        _isVideoBuffering.value = buffering
    }

    fun isThrottled(): Boolean =
        _isVideoBuffering.value || SystemClock.uptimeMillis() < cooldownUntilUptimeMs.get()

    suspend fun awaitClear(maxWaitMs: Long = 8_000L): Boolean {
        val deadlineMs = SystemClock.uptimeMillis() + maxWaitMs.coerceAtLeast(0L)
        while (true) {
            val remainingBudget = deadlineMs - SystemClock.uptimeMillis()
            if (remainingBudget <= 0L) return !isThrottled()
            if (_isVideoBuffering.value) {
                val cleared = withTimeoutOrNull(remainingBudget) {
                    _isVideoBuffering.first { !it }
                    true
                } ?: false
                if (!cleared) return false
            }
            val nowMs = SystemClock.uptimeMillis()
            val remainingCooldown = cooldownUntilUptimeMs.get() - nowMs
            if (remainingCooldown <= 0L) {
                if (!_isVideoBuffering.value) return true
                continue
            }
            val sleepMs = minOf(remainingCooldown, (deadlineMs - nowMs).coerceAtLeast(0L))
            if (sleepMs <= 0L) return !isThrottled()
            delay(sleepMs)
            if (!isThrottled()) return true
        }
    }
}

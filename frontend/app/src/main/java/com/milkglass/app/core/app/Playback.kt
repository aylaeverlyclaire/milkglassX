package com.milkglass.app.core.app

import com.milkglass.app.core.data.DAY_MS
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.model.HistoryEntry
import com.milkglass.app.core.remote.SkipTimes
import com.milkglass.app.core.storage.PersistedState
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

fun endingRange(a: Anime, durationSeconds: Int): Pair<Double, Double> =
    (durationSeconds - 100 - (a.id % 3) * 10).toDouble() to durationSeconds.toDouble()

fun savePlayback(s: PersistedState, a: Anime, episode: Int, rawPosition: Double, durationSeconds: Int, now: Long): PersistedState {
    
    
    
    if (!rawPosition.isFinite()) return s
    val position = rawPosition.coerceIn(0.0, durationSeconds.toDouble())
    val id = a.id.toString()
    val e = episode.toString()
    val progress = s.progress.toMutableMap()
    val own = (progress[id] ?: emptyMap()).toMutableMap()
    val v = floor(position)
    var resume = s.resumePositions
    if (position >= 3) {
        val r = s.resumePositions.toMutableMap()
        val m = (r[id] ?: emptyMap()).toMutableMap()
        m[e] = if (position >= durationSeconds - 1) 0.0 else v
        r[id] = m
        resume = r
    }
    val cur = own[e] ?: 0.0
    if ((v >= 3 && v >= cur) || position >= durationSeconds - 1) own[e] = max(cur, v)
    progress[id] = own
    var lastWatched = s.lastWatched
    var history = s.history
    if (position >= 3 && !((own[e] ?: 0.0) > durationSeconds * .9 && position < 3)) {
        lastWatched = s.lastWatched + (id to now)
        val idx = history.indexOfFirst { it.id == a.id && it.episode == episode }
        history = if (idx >= 0) history.toMutableList().also { it[idx] = it[idx].copy(watchedAt = now) }
        else listOf(HistoryEntry(a.id, episode, now)) + history
    }
    return s.copy(progress = progress, resumePositions = resume, lastWatched = lastWatched, history = history)
}

class PlayerEngine(
    private val ctl: AppController,
    val anime: Anime,
    val episode: Int,
    startPosition: Double,
) {
    var position by mutableDoubleStateOf(startPosition)
    var isPlaying by mutableStateOf(false)
    var speed = 1.0
    var adSecondsLeft by mutableDoubleStateOf(0.0)
    var noticeTimer by mutableDoubleStateOf(0.0)
    var controlsTimer = 3.0
    var openingSkipped by mutableStateOf(false)
    var endingSkipped by mutableStateOf(false)
    
    var skipTimes by mutableStateOf<SkipTimes?>(ctl.initialSkipTimes(anime))
    
    var skipPrompt by mutableStateOf<SkipKind?>(null)
        private set
    private val prompter = SkipPrompter()
    var hasEnded by mutableStateOf(false)
    var isFullscreen by mutableStateOf(false)
    var bufferTimer by mutableDoubleStateOf(0.0)
    var isOffline by mutableStateOf(false)
    
    var faded by mutableStateOf(false)
    var left = false
        private set

    
    var nativeStreamActive by mutableStateOf(false)
    var nativeBuffering by mutableStateOf(true)
    var nativeDurationSeconds by mutableStateOf<Int?>(null)
    var onNativeSeekMs: ((Long) -> Unit)? = null
    var onNativePlayPause: ((Boolean) -> Unit)? = null
    var onNativeRetry: (() -> Unit)? = null

    val duration: Int get() = nativeDurationSeconds ?: 0
    val opening: IntRange get() = anime.openingRange[0]..anime.openingRange[1]
    val ending: Pair<Double, Double> get() = endingRange(anime, duration)
    val inOpening: Boolean get() = position >= anime.openingRange[0] && position < anime.openingRange[1]
    val inEnding: Boolean get() = position >= ending.first && position < duration

    
    val skipButtonKind: SkipKind? get() =
        if (skipTimes == null) (if (inEnding) SkipKind.Ending else if (inOpening) SkipKind.Opening else null) else skipPrompt

    private val autoSkipOn: Boolean get() = ctl.features.autoSkip && ctl.persisted.settings.autoSkipOpeningEnding != 0

    private fun isPlayerOffline(): Boolean =
        !ctl.online && ctl.persisted.downloads["${anime.id}.$episode"] != 100

    fun save() {
        if (!nativeStreamActive || duration <= 0) return
        ctl.persisted = savePlayback(ctl.persisted, anime, episode, position, duration, ctl.now())
    }

    fun onLeave() {
        if (left) return
        left = true
        save()
    }

    
    fun setControls(on: Boolean) {
        faded = !on
        if (on) controlsTimer = 3.0
    }

    
    fun updateUi() {
        val hh = controlsTimer <= 0 && isPlaying && adSecondsLeft <= 0 && !isOffline
        faded = hh
    }

    fun seekBy(d: Double) {
        position = max(0.0, min(duration.toDouble(), position + d))
        hasEnded = false
        noticeTimer = 0.0
        controlsTimer = 3.0
        onNativeSeekMs?.invoke((position * 1000.0).toLong())
        updateUi()
    }

    
    fun seekTo(p: Double) {
        if (!p.isFinite()) return
        position = p.coerceIn(0.0, duration.toDouble())
        hasEnded = false
        noticeTimer = 0.0
        onNativeSeekMs?.invoke((position * 1000.0).toLong())
        updateUi()
    }

    fun togglePlayPause() {
        if (!nativeStreamActive) return
        if (hasEnded) {
            position = 0.0
            hasEnded = false
            onNativeSeekMs?.invoke(0L)
        }
        isPlaying = !isPlaying
        controlsTimer = 3.0
        onNativePlayPause?.invoke(isPlaying)
        updateUi()
    }

    
    fun skip() {
        val st = skipTimes
        if (st != null) {
            val dur = duration.toDouble()
            when (skipPrompt) {
                SkipKind.Ending -> {
                    val end = st.ending?.end ?: dur
                    endingSkipped = true
                    position = if (end >= dur - 1) dur else end
                    ctl.showToast(ctl.tr.t("player.endingSkipped"))
                }
                SkipKind.Opening -> {
                    position = min(dur, st.opening?.end ?: return)
                    openingSkipped = true
                }
                null -> return
            }
            prompter.dismiss()
            skipPrompt = null
            onNativeSeekMs?.invoke((position * 1000.0).toLong())
            updateUi()
            return
        }
        if (position >= ending.first) {
            endingSkipped = true
            position = duration.toDouble()
            ctl.showToast(ctl.tr.t("player.endingSkipped"))
        } else {
            position = anime.openingRange[1].toDouble()
            openingSkipped = true
        }
        onNativeSeekMs?.invoke((position * 1000.0).toLong())
        updateUi()
    }

    
    private fun updateSkipPrompt(st: SkipTimes, dt: Double) {
        skipPrompt = prompter.update(st, position, dt)
        if (!autoSkipOn) return
        val dur = duration.toDouble()
        val op = st.opening
        val ed = st.ending
        if (!openingSkipped && op != null && op.contains(position)) {
            position = min(dur, op.end)
            openingSkipped = true
            onNativeSeekMs?.invoke((position * 1000.0).toLong())
            ctl.showToast(ctl.tr.t("player.openingSkipped"))
        } else if (!endingSkipped && ed != null && ed.contains(position)) {
            endingSkipped = true
            position = if (ed.end >= dur - 1) dur else ed.end
            onNativeSeekMs?.invoke((position * 1000.0).toLong())
            ctl.showToast(ctl.tr.t("player.endingSkipped"))
        } else return
        prompter.dismiss()
        skipPrompt = null
    }

    fun playNextNow() {
        ctl.nextPlayableEpisode(anime, episode)?.let { ctl.openPlayer(anime.id, it, 0.0) }
    }

    fun cancelAutoNext() { noticeTimer = 0.0; hasEnded = true; updateUi() }

    fun replay() {
        position = 0.0
        hasEnded = false
        isPlaying = true
        onNativeSeekMs?.invoke(0L)
        onNativePlayPause?.invoke(true)
        updateUi()
    }

    fun retry() {
        if (!ctl.online) ctl.showToast(ctl.tr.t("player.stillOffline"))
        else {
            isOffline = false
            nativeBuffering = true
            onNativeRetry?.invoke()
            updateUi()
        }
    }

    fun toggleFullscreen() { isFullscreen = !isFullscreen }

    
    fun syncFromNativePlayer(currentPositionMs: Long) {
        if (left || !nativeStreamActive) return
        val posSec = (currentPositionMs / 1000.0).coerceIn(0.0, duration.toDouble())
        position = posSec
        bufferTimer = 0.0
    }

    fun tick() {
        if (left) return
        val d4 = .25
        if (controlsTimer > 0) {
            controlsTimer -= d4
            if (controlsTimer <= 0 && isPlaying && adSecondsLeft <= 0) setControls(false)
        }
        if (isPlayerOffline()) { isOffline = true; controlsTimer = 3.0; updateUi(); return }
        if (isOffline) { isOffline = false }
        if (adSecondsLeft > 0) {
            adSecondsLeft -= d4
            if (adSecondsLeft <= 0) adSecondsLeft = 0.0
            updateUi(); return
        }
        if (noticeTimer > 0) {
            noticeTimer -= d4
            if (noticeTimer <= 0) {
                noticeTimer = 0.0
                ctl.nextPlayableEpisode(anime, episode)?.let { ctl.openPlayer(anime.id, it, 0.0) }
                return
            }
            updateUi(); return
        }
        if (!isPlaying || hasEnded) return
        if (!nativeStreamActive || nativeBuffering || duration <= 0) return
        val st = skipTimes
        if (st != null) updateSkipPrompt(st, d4)
        else {
            if (autoSkipOn && !openingSkipped && position >= anime.openingRange[0] && position < anime.openingRange[1]) {
                position = anime.openingRange[1].toDouble()
                openingSkipped = true
                onNativeSeekMs?.invoke((position * 1000.0).toLong())
                ctl.showToast(ctl.tr.t("player.openingSkipped"))
            }
            if (autoSkipOn && !endingSkipped && position >= ending.first) {
                endingSkipped = true
                position = duration.toDouble()
                onNativeSeekMs?.invoke((position * 1000.0).toLong())
                ctl.showToast(ctl.tr.t("player.endingSkipped"))
            }
        }
        if (floor(position * 4).toLong() % 4 == 0L) save()
        if (position >= duration) {
            hasEnded = true
            isPlaying = false
            save()
            val n = ctl.nextPlayableEpisode(anime, episode)
            if (n != null && ctl.persisted.settings.autoplayNext != 0) noticeTimer = 5.0 else hasEnded = true
        }
        updateUi()
    }
}

private const val SMART_RESUME_MIN_SECONDS = 10.0

fun smartResume(saved: Double, lastWatchedAt: Long, now: Long): Double {
    if (!saved.isFinite() || saved < SMART_RESUME_MIN_SECONDS) return saved
    val gap = now - lastWatchedAt
    val back = when {
        lastWatchedAt <= 0L || gap < DAY_MS -> 3.0
        gap < 7 * DAY_MS -> 8.0
        else -> 15.0
    }
    return max(0.0, saved - back)
}

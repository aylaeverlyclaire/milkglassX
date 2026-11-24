package id.my.milkglass.player

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import id.my.milkglass.source.MediaContainer
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import id.my.milkglass.EngineRuntime
import id.my.milkglass.download.EndpointHealthScorer
import id.my.milkglass.resolver.FailureReason
import id.my.milkglass.resolver.LocalStreamSession
import id.my.milkglass.resolver.ResolvedStream
import id.my.milkglass.resolver.PlaybackTelemetryReport
import id.my.milkglass.resolver.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class PlaybackPhase {
    LOADING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

data class PlaybackRequest(
    val slug: String,
    val preferredResolution: String = "1080p",
    val anilistId: Int? = null,
    val title: String = "",
    val romajiTitle: String = "",
    val startPositionMs: Long = 0L,
    val preferCompatibleContainer: Boolean = false,
    val urlRefreshAttempts: Int = 0,
    val autoPlay: Boolean = true
)

data class PlaybackProgress(
    val sessionId: Long = 0L,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L
)

data class PlayerSnapshot(
    val sessionId: Long = 0L,
    val phase: PlaybackPhase = PlaybackPhase.LOADING,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val videoAspectRatio: Float = 16f / 9f,
    val activeResolution: String = "1080p",
    val availableResolutions: List<String> = listOf("Auto", "1080p", "720p", "480p"),
    val resolvedSourceId: String? = null,
    val resolvedHoster: String? = null,
    val subtitleLines: List<String> = emptyList(),
    val error: FriendlyPlayerError? = null
) {
    val isReady: Boolean
        get() = phase == PlaybackPhase.PLAYING || phase == PlaybackPhase.PAUSED || phase == PlaybackPhase.ENDED
    val isLoading: Boolean
        get() = phase == PlaybackPhase.LOADING
    val isPlaying: Boolean
        get() = phase == PlaybackPhase.PLAYING
}

sealed class PlayerState {
    object Idle : PlayerState()
    object Buffering : PlayerState()
    data class Playing(val isPlaying: Boolean) : PlayerState()
    object Paused : PlayerState()
    object Ended : PlayerState()
    data class Error(val error: FriendlyPlayerError) : PlayerState()
}

@OptIn(UnstableApi::class)
class PlayerManager(
    context: Context,
    private val backendBaseUrl: String = StreamResolver.DEFAULT_BACKEND_BASE_URL,
    private val masterAppSecret: ByteArray = ByteArray(0),
    private val authTokenProvider: () -> String? = { null },
    private val resolver: StreamResolver = if (backendBaseUrl == StreamResolver.DEFAULT_BACKEND_BASE_URL) {
        EngineRuntime.get(context.applicationContext).streamResolver
    } else {
        StreamResolver(
            context = context.applicationContext,
            backendBaseUrl = backendBaseUrl,
            masterAppSecret = masterAppSecret,
            authTokenProvider = authTokenProvider
        )
    }
) {
    private val appContext: Context = context.applicationContext

    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var attachedTextureView: TextureView? = null

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeStreamSession: LocalStreamSession? = null
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var fullCacheJob: Job? = null
    private var enrichJob: Job? = null
    private var lastRequest: PlaybackRequest? = null
    private var activeCacheSlug: String? = null
    private val activeCacheKeys = CopyOnWriteArraySet<String>()

    private val sessionCounter = AtomicLong(0L)
    @Volatile private var activeSessionId: Long = 0L
    @Volatile private var loadStartedElapsedMs: Long = 0L
    @Volatile private var resolveCompletedElapsedMs: Long = 0L
    @Volatile private var telemetryReportedSessionId: Long = -1L
    @Volatile private var released = false
    private var isScrubbing = false

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Buffering)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _availableResolutions = MutableStateFlow(listOf("Auto", "1080p", "720p", "480p"))
    val availableResolutions: StateFlow<List<String>> = _availableResolutions.asStateFlow()

    private val _subtitleLines = MutableStateFlow<List<String>>(emptyList())
    val subtitleLines: StateFlow<List<String>> = _subtitleLines.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(16f / 9f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    val player: ExoPlayer?
        get() = exoPlayer

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val sid = activeSessionId
            if (sid == 0L || _snapshot.value.sessionId != sid) return
            when (playbackState) {
                Player.STATE_IDLE -> Unit
                Player.STATE_BUFFERING -> {
                    setVideoBuffering(true)
                    _playerState.value = PlayerState.Buffering
                    _snapshot.update { it.copy(phase = PlaybackPhase.LOADING, error = null) }
                }
                Player.STATE_READY -> {
                    setVideoBuffering(false)
                    activeStreamSession?.let { sess ->
                        if (sess.resolvedSourceId != "offline") {
                            EndpointHealthScorer.recordSuccess(sess.resolvedHoster)
                            EndpointHealthScorer.recordSourceSuccess(sess.resolvedSourceId)
                        }
                    }
                    val playing = exoPlayer?.isPlaying == true
                    val dur = exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: _snapshot.value.durationMs
                    val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: _snapshot.value.positionMs
                    _playerState.value = if (playing) PlayerState.Playing(true) else PlayerState.Paused
                    _snapshot.update {
                        it.copy(
                            phase = if (playing) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
                            positionMs = pos,
                            durationMs = dur,
                            error = null
                        )
                    }
                }
                Player.STATE_ENDED -> {
                    setVideoBuffering(false)
                    _playerState.value = PlayerState.Ended
                    _snapshot.update {
                        it.copy(
                            phase = PlaybackPhase.ENDED,
                            positionMs = it.durationMs.coerceAtLeast(it.positionMs)
                        )
                    }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val sid = activeSessionId
            if (sid == 0L || _snapshot.value.sessionId != sid) return
            if (exoPlayer?.playbackState == Player.STATE_READY) {
                _playerState.value = if (isPlaying) PlayerState.Playing(true) else PlayerState.Paused
                _snapshot.update {
                    it.copy(phase = if (isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val ratio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                if (ratio.isFinite() && ratio > 0.2f && ratio < 4.5f) {
                    _videoAspectRatio.value = ratio
                    _snapshot.update { it.copy(videoAspectRatio = ratio) }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            val sid = activeSessionId
            if (sid == 0L || _snapshot.value.sessionId != sid) return
            emitPlaybackTelemetry(sid = sid, failed = false)
        }

        override fun onCues(cueGroup: CueGroup) {
            val cleaned = cueGroup.cues
                .flatMap { cue -> sanitizeSubtitleText(cue.text?.toString().orEmpty()) }
                .distinct()
            _subtitleLines.value = cleaned
            _snapshot.update { it.copy(subtitleLines = cleaned) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val sid = activeSessionId
            if (sid == 0L || _snapshot.value.sessionId != sid) return
            activeStreamSession?.let { sess ->
                if (sess.resolvedSourceId != "offline") {
                    EndpointHealthScorer.recordFailure(sess.resolvedHoster)
                }
            }
            val friendly = ErrorMapper.mapExoPlayerError(error, appContext)
            val req = lastRequest
            val wasPlaying = exoPlayer?.playWhenReady ?: true
            val isMidStreamNotFound = friendly.reason == FailureReason.NOT_FOUND && (_snapshot.value.positionMs > 0L)
            val isTransportError = friendly.reason == FailureReason.NETWORK ||
                friendly.reason == FailureReason.TIMEOUT ||
                friendly.reason == FailureReason.HTTP_403 ||
                friendly.reason == FailureReason.EXPIRED ||
                isMidStreamNotFound
            val badDirectUrl = activeStreamSession?.candidates?.firstOrNull()?.directUrl
                ?: activeStreamSession?.localHttpUrl
            if (!badDirectUrl.isNullOrBlank()) {
                id.my.milkglass.source.DeadCandidateCache.markDead(badDirectUrl)
                if (req != null && (friendly.reason == FailureReason.HTTP_403 || friendly.reason == FailureReason.NOT_FOUND || friendly.reason == FailureReason.TIMEOUT)) {
                    val reportCode = when (friendly.reason) {
                        FailureReason.HTTP_403 -> 403
                        FailureReason.NOT_FOUND -> 404
                        else -> 504
                    }
                    val searchTitle = req.romajiTitle.ifBlank { req.title }.trim()
                    val epNum = resolver.extractEpisodeNumber(req.slug)
                    if (searchTitle.length >= 2 && epNum >= 0) {
                        resolver.reportDeadDirectUrl(searchTitle, epNum, badDirectUrl, reportCode)
                    }
                }
            }
            val currentSession = activeStreamSession
            if (req != null && currentSession != null && (friendly.reason == FailureReason.CODEC || isTransportError)) {
                val backup = selectNextFailoverCandidate(
                    candidates = currentSession.candidates,
                    failedUrl = badDirectUrl.orEmpty(),
                    preferCompatibleContainer = friendly.reason == FailureReason.CODEC || req.preferCompatibleContainer
                )
                if (backup != null) {
                    val remaining = reorderFailoverCandidates(currentSession.candidates, badDirectUrl.orEmpty(), backup)
                    val backupHeaders = buildMap {
                        currentSession.headers["User-Agent"]?.let { put("User-Agent", it) }
                        if (!backup.referer.isNullOrBlank()) put("Referer", backup.referer)
                        putAll(backup.extraHeaders)
                    }
                    val backupIsHls = backup.isHls || backup.container == MediaContainer.HLS
                    activeStreamSession = currentSession.copy(
                        localHttpUrl = backup.directUrl,
                        activeResolution = backup.quality.label,
                        resolvedSourceId = backup.source.id,
                        resolvedHoster = backup.hoster,
                        isHls = backupIsHls,
                        headers = backupHeaders,
                        candidates = remaining
                    )
                    _snapshot.update {
                        if (it.sessionId != sid) it else it.copy(
                            activeResolution = backup.quality.label,
                            resolvedSourceId = backup.source.id,
                            resolvedHoster = backup.hoster,
                            error = null
                        )
                    }
                    val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
                    prepareMedia(
                        mediaUrl = backup.directUrl,
                        headers = backupHeaders,
                        isHls = backupIsHls,
                        startPositionMs = resumeMs,
                        cacheKey = null
                    )
                    return
                }
            }
            if (req != null && friendly.reason == FailureReason.CODEC && (!req.preferCompatibleContainer || req.urlRefreshAttempts < 1)) {
                val keysToClear = activeCacheKeys.toList()
                activeCacheKeys.clear()
                evictStreamCacheKeys(appContext, keysToClear)
                resolver.invalidateCache(req.slug)
                val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
                val nextAttempts = if (req.preferCompatibleContainer) req.urlRefreshAttempts + 1 else req.urlRefreshAttempts
                load(req.copy(preferCompatibleContainer = true, urlRefreshAttempts = nextAttempts, startPositionMs = resumeMs, autoPlay = wasPlaying))
                return
            }
            if (req != null && isTransportError && req.urlRefreshAttempts < 2) {
                val keysToClear = activeCacheKeys.toList()
                activeCacheKeys.clear()
                evictStreamCacheKeys(appContext, keysToClear)
                resolver.invalidateCache(req.slug)
                val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
                load(req.copy(urlRefreshAttempts = req.urlRefreshAttempts + 1, startPositionMs = resumeMs, autoPlay = wasPlaying))
                return
            }
            setVideoBuffering(false)
            emitPlaybackTelemetry(
                sid = sid,
                failed = true,
                errorCode = error.errorCodeName,
                errorMessage = friendly.userMessage
            )
            _playerState.value = PlayerState.Error(friendly)
            _snapshot.update { it.copy(phase = PlaybackPhase.ERROR, error = friendly) }
        }
    }

    fun initialize() {
        if (released || exoPlayer != null) return

        val bandwidthMeter = DefaultBandwidthMeter.Builder(appContext).build()
        val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(appContext, videoTrackSelectionFactory).apply {
            setParameters(
                buildUponParameters()
                    .clearVideoSizeConstraints()
                    .setPreferredTextLanguages("id", "ind", "en", "eng")
                    .setPreferredAudioLanguages("ja", "jpn")
                    .setSelectUndeterminedTextLanguage(true)
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 200, 1_000)
            .setBackBuffer(10_000, true)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { player ->
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                player.addListener(playerListener)
                attachedTextureView?.let { player.setVideoTextureView(it) }
            }

        startPositionTicker()
    }

    fun attachTextureView(view: TextureView?) {
        if (attachedTextureView === view) return
        attachedTextureView = view
        exoPlayer?.setVideoTextureView(view)
    }

    fun detachTextureView(view: TextureView) {
        if (attachedTextureView !== view) return
        attachedTextureView = null
        exoPlayer?.setVideoTextureView(null)
    }

    fun load(request: PlaybackRequest) {
        if (released) return
        if (exoPlayer == null) initialize()

        val sid = sessionCounter.incrementAndGet()
        activeSessionId = sid
        loadStartedElapsedMs = SystemClock.elapsedRealtime()
        resolveCompletedElapsedMs = 0L
        lastRequest = request

        cancelPrebuffer()
        fullCacheJob?.cancel()
        fullCacheJob = null
        enrichJob?.cancel()
        enrichJob = null

        val prevSlug = activeCacheSlug
        if (prevSlug != null && prevSlug != request.slug) {
            val keysToEvict = activeCacheKeys.toList()
            activeCacheKeys.clear()
            if (keysToEvict.isNotEmpty()) {
                playerScope.launch(Dispatchers.IO) {
                    evictStreamCacheKeys(appContext, keysToEvict)
                }
            }
        }
        activeCacheSlug = request.slug

        setVideoBuffering(true)
        loadJob?.cancel()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        stopStreamSession()

        _subtitleLines.value = emptyList()
        _playerState.value = PlayerState.Buffering
        _snapshot.value = PlayerSnapshot(
            sessionId = sid,
            phase = PlaybackPhase.LOADING,
            positionMs = request.startPositionMs.coerceAtLeast(0L),
            durationMs = 0L,
            bufferedPositionMs = 0L,
            videoAspectRatio = _videoAspectRatio.value,
            activeResolution = request.preferredResolution,
            availableResolutions = _availableResolutions.value,
            subtitleLines = emptyList(),
            error = null
        )

        loadJob = playerScope.launch {
            try {
                val session = resolver.resolveAndStartStream(
                    slug = request.slug,
                    preferredRes = if (request.preferredResolution == "Auto") "720p" else request.preferredResolution,
                    title = request.title,
                    romajiTitle = request.romajiTitle,
                    anilistId = request.anilistId,
                    preferCompatibleContainer = request.preferCompatibleContainer,
                    scope = playerScope
                )
                if (released || !isActive || activeSessionId != sid) {
                    session.closeSession()
                    return@launch
                }

                resolveCompletedElapsedMs = SystemClock.elapsedRealtime()
                activeStreamSession = session
                val availRes = (listOf("Auto") + session.availableResolutions).distinct()
                _availableResolutions.value = availRes
                _snapshot.update {
                    if (it.sessionId != sid) it else it.copy(
                        activeResolution = session.activeResolution,
                        availableResolutions = availRes,
                        resolvedSourceId = session.resolvedSourceId,
                        resolvedHoster = session.resolvedHoster
                    )
                }

                if (session.isHls) {
                    val maxH = when {
                        request.preferredResolution.contains("1080") -> 1080
                        request.preferredResolution.contains("720") -> 720
                        request.preferredResolution.contains("480") -> 480
                        else -> 0
                    }
                    setMaxVideoResolution(maxH)
                } else {
                    setMaxVideoResolution(0)
                }
                val streamCacheKey = if (session.isHls || session.localHttpUrl.startsWith("file://", ignoreCase = true)) {
                    null
                } else {
                    buildStreamCacheKey(request.slug, session.activeResolution, request.preferCompatibleContainer)
                }
                if (streamCacheKey != null) activeCacheKeys.add(streamCacheKey)
                prepareMedia(
                    mediaUrl = session.localHttpUrl,
                    headers = session.headers,
                    isHls = session.isHls,
                    startPositionMs = request.startPositionMs,
                    cacheKey = streamCacheKey
                )
                if (streamCacheKey != null) {
                    startBackgroundFullCache(session, streamCacheKey, sid)
                }
                if (!session.localHttpUrl.startsWith("file://", ignoreCase = true)) {
                    enrichJob?.cancel()
                    enrichJob = resolver.enrichAvailableResolutionsAsync(
                        slug = request.slug,
                        title = request.title,
                        romajiTitle = request.romajiTitle,
                        currentResolutions = session.availableResolutions,
                        scope = playerScope
                    ) { mergedLabels ->
                        if (released || activeSessionId != sid) return@enrichAvailableResolutionsAsync
                        val mergedAvail = (listOf("Auto") + mergedLabels).distinct()
                        _availableResolutions.value = mergedAvail
                        _snapshot.update {
                            if (it.sessionId != sid) it else it.copy(availableResolutions = mergedAvail)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (released || activeSessionId != sid) return@launch
                setVideoBuffering(false)
                val friendly = ErrorMapper.mapResolverError(e, appContext)
                emitPlaybackTelemetry(
                    sid = sid,
                    failed = true,
                    errorCode = friendly.reason.name,
                    errorMessage = friendly.userMessage,
                    providerDiagnostics = e.message.orEmpty()
                )
                _playerState.value = PlayerState.Error(friendly)
                _snapshot.update {
                    if (it.sessionId != sid) it else it.copy(
                        phase = PlaybackPhase.ERROR,
                        error = friendly
                    )
                }
            }
        }
    }

    fun switchResolution(newResolution: String) {
        val current = lastRequest ?: return
        val activeSession = activeStreamSession
        if (activeSession != null && activeSession.isHls) {
            val maxH = when {
                newResolution.contains("1080") -> 1080
                newResolution.contains("720") -> 720
                newResolution.contains("480") -> 480
                newResolution.contains("360") -> 360
                else -> 0
            }
            lastRequest = current.copy(preferredResolution = newResolution)
            setMaxVideoResolution(maxH)
            _snapshot.update {
                it.copy(activeResolution = if (newResolution == "Auto") activeSession.activeResolution else newResolution)
            }
            return
        }
        val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
        load(
            current.copy(
                preferredResolution = newResolution,
                startPositionMs = resumeMs,
                preferCompatibleContainer = false
            )
        )
    }

    fun retry() {
        val current = lastRequest ?: return
        val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
        load(current.copy(startPositionMs = resumeMs, preferCompatibleContainer = false, urlRefreshAttempts = 0))
    }

    fun playStreamEpisode(
        slug: String,
        preferredResolution: String = "1080p",
        anilistId: Int? = null,
        title: String? = null,
        romajiTitle: String? = null,
        startPositionMs: Long = 0L
    ) {
        load(
            PlaybackRequest(
                slug = slug,
                preferredResolution = preferredResolution,
                anilistId = anilistId,
                title = title.orEmpty(),
                romajiTitle = romajiTitle.orEmpty(),
                startPositionMs = startPositionMs
            )
        )
    }

    fun playStream(
        resolvedStream: ResolvedStream,
        subtitleUrl: String? = null,
        startPositionMs: Long = 0L,
        cacheKey: String? = null
    ) {
        if (released) return
        if (exoPlayer == null) initialize()

        val sid = sessionCounter.incrementAndGet()
        activeSessionId = sid
        lastRequest = null
        loadJob?.cancel()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        stopStreamSession()

        val qualityLabel = if (resolvedStream.source.quality > 0) {
            "${resolvedStream.source.quality}p"
        } else {
            _snapshot.value.activeResolution
        }
        _subtitleLines.value = emptyList()
        _playerState.value = PlayerState.Buffering
        _snapshot.value = PlayerSnapshot(
            sessionId = sid,
            phase = PlaybackPhase.LOADING,
            positionMs = startPositionMs.coerceAtLeast(0L),
            durationMs = 0L,
            bufferedPositionMs = 0L,
            videoAspectRatio = _videoAspectRatio.value,
            activeResolution = qualityLabel,
            availableResolutions = _availableResolutions.value,
            resolvedSourceId = resolvedStream.source.type,
            resolvedHoster = resolvedStream.source.releaseGroup,
            subtitleLines = emptyList(),
            error = null
        )

        prepareMedia(
            mediaUrl = resolvedStream.directUrl,
            headers = resolvedStream.headers,
            isHls = resolvedStream.directUrl.contains(".m3u8", ignoreCase = true),
            startPositionMs = startPositionMs,
            cacheKey = cacheKey
        )
    }

    private fun prepareMedia(
        mediaUrl: String,
        headers: Map<String, String>,
        isHls: Boolean,
        startPositionMs: Long,
        cacheKey: String?
    ) {
        val playerInstance = exoPlayer ?: return
        val sharedOkHttp = EngineRuntime.get(appContext).clients.streamingClient
        val httpProps = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val httpFactory = OkHttpDataSource.Factory(sharedOkHttp)
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(httpProps)

        val upstreamFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val isLocalFile = mediaUrl.startsWith("file://", ignoreCase = true)
        val sessionCandidates = activeStreamSession?.candidates.orEmpty()
        val currentReq = lastRequest
        val effectiveUpstream: DataSource.Factory = if (!isLocalFile && !isHls && sessionCandidates.isNotEmpty()) {
            FailoverHttpDataSource.Factory(
                client = sharedOkHttp,
                candidates = sessionCandidates,
                defaultUserAgent = headers["User-Agent"] ?: DEFAULT_USER_AGENT,
                onCandidateFailure = { cand, code ->
                    if (code in listOf(403, 404, 410, 429) || code >= 500) {
                        val req = currentReq
                        if (req != null) {
                            val title = req.romajiTitle.ifBlank { req.title }
                            val ep = resolver.extractEpisodeNumber(req.slug)
                            resolver.reportDeadDirectUrl(title, ep, cand.directUrl, code)
                        }
                    }
                }
            )
        } else {
            upstreamFactory
        }
        val finalFactory: DataSource.Factory = if (!isLocalFile && !isHls) {
            val streamCache = getStreamCache(appContext)
            if (streamCache != null) {
                CacheDataSource.Factory()
                    .setCache(streamCache)
                    .setUpstreamDataSourceFactory(effectiveUpstream)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                effectiveUpstream
            }
        } else {
            effectiveUpstream
        }

        val targetPlayUrl = if (!isLocalFile && !isHls && sessionCandidates.isNotEmpty()) {
            sessionCandidates.first().directUrl
        } else {
            mediaUrl
        }
        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(targetPlayUrl))
        if (isHls || targetPlayUrl.lowercase().contains(".m3u8")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        if (!cacheKey.isNullOrBlank() && !isLocalFile && !isHls) {
            mediaItemBuilder.setCustomCacheKey(cacheKey)
        }

        val mediaSource = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(finalFactory)
            .createMediaSource(mediaItemBuilder.build())

        playerInstance.setMediaSource(mediaSource, startPositionMs.coerceAtLeast(0L))
        if (false && startPositionMs > 0L) {
            playerInstance.seekTo(startPositionMs)
        }
        playerInstance.prepare()
        playerInstance.playWhenReady = lastRequest?.autoPlay ?: true
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = playerScope.launch {
            while (isActive && !released) {
                val p = exoPlayer
                val playing = p?.isPlaying == true
                delay(if (playing) 250L else 500L)
                if (p == null) continue
                val snap = _snapshot.value
                if (snap.isReady) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: snap.durationMs
                    val buf = p.bufferedPosition.coerceAtLeast(0L)
                    val cur = _progress.value
                    val sid = activeSessionId
                    if (cur.sessionId != sid || cur.positionMs != pos || cur.durationMs != dur || cur.bufferedPositionMs != buf) {
                        _progress.value = PlaybackProgress(
                            sessionId = sid,
                            positionMs = pos,
                            durationMs = dur,
                            bufferedPositionMs = buf
                        )
                    }
                }
            }
        }
    }

    fun setMaxVideoResolution(maxHeight: Int) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
        if (maxHeight <= 0) params.clearVideoSizeConstraints()
        else params.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        selector.setParameters(params)
    }

    fun setScrubbing(active: Boolean) {
        val p = exoPlayer ?: return
        if (isScrubbing == active) return
        isScrubbing = active
        p.setSeekParameters(if (active) SeekParameters.PREVIOUS_SYNC else SeekParameters.CLOSEST_SYNC)
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (!_snapshot.value.isReady) return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0L)
            p.playWhenReady = true
        } else if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val p = exoPlayer ?: return
        val dur = _snapshot.value.durationMs
        if (dur <= 0L) return
        val clamped = positionMs.coerceIn(0L, dur)
        p.seekTo(clamped)
        _snapshot.update { it.copy(positionMs = clamped) }
    }

    fun seekBy(deltaMs: Long) {
        val basePos = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
        seekTo(basePos + deltaMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer?.setPlaybackSpeed(speed.coerceIn(0.25f, 3.0f))
    }

    fun getCurrentPosition(): Long = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun getDuration(): Long = exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L

    /**
     * Opportunistic background full-episode caching untuk optimalisasi seekbar (maju-mundur tanpa reload).
     * Disesuaikan dengan ruang kosong perangkat (>= 512 MiB) dan selalu mengalah saat player sedang buffering.
     */
    private fun startBackgroundFullCache(session: LocalStreamSession, cacheKey: String, sessionId: Long) {
        fullCacheJob?.cancel()
        fullCacheJob = playerScope.launch(Dispatchers.IO) {
            val streamCache = getStreamCache(appContext) ?: return@launch
            val cacheDir = File(appContext.cacheDir, "mkv_stream_cache")
            val availableDiskBytes = runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrDefault(0L)
            if (availableDiskBytes < MIN_FREE_DISK_FOR_FULL_CACHE_BYTES) return@launch

            val maxBudgetBytes = computeAdaptiveCacheBytes(availableDiskBytes)
            // Tunggu pemutaran awal stabil terlebih dahulu
            delay(2_500L)

            val sharedOkHttp = EngineRuntime.get(appContext).clients.streamingClient
            val upstreamFactory: DataSource.Factory = if (session.candidates.isNotEmpty()) {
                FailoverHttpDataSource.Factory(
                    client = sharedOkHttp,
                    candidates = session.candidates,
                    defaultUserAgent = session.headers["User-Agent"] ?: DEFAULT_USER_AGENT
                )
            } else {
                OkHttpDataSource.Factory(sharedOkHttp)
                    .setUserAgent(session.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                    .setDefaultRequestProperties(session.headers)
            }
            val cacheDs = CacheDataSource.Factory()
                .setCache(streamCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            val targetUri = Uri.parse(session.candidates.firstOrNull()?.directUrl ?: session.localHttpUrl)
            val chunkBytes = 2L * 1024L * 1024L
            var offset = 0L
            val buf = ByteArray(64 * 1024)

            while (isActive && !released && activeSessionId == sessionId) {
                while (isActive && !released && activeSessionId == sessionId && isVideoBuffering.value) {
                    delay(400L)
                }
                if (!isActive || released || activeSessionId != sessionId) break

                val totalLen = runCatching {
                    androidx.media3.datasource.cache.ContentMetadata.getContentLength(streamCache.getContentMetadata(cacheKey))
                }.getOrDefault(C.LENGTH_UNSET.toLong())

                if (totalLen > 0L) {
                    if (offset >= totalLen || totalLen > maxBudgetBytes) break
                } else if (offset >= maxBudgetBytes) {
                    break
                }

                val len = if (totalLen > 0L) minOf(chunkBytes, totalLen - offset) else chunkBytes
                val alreadyCached = runCatching { streamCache.getCachedBytes(cacheKey, offset, len) }.getOrDefault(0L)
                if (alreadyCached >= len) {
                    offset += len
                    continue
                }

                val spec = DataSpec.Builder()
                    .setUri(targetUri)
                    .setPosition(offset)
                    .setLength(len)
                    .setKey(cacheKey)
                    .build()
                val writer = CacheWriter(cacheDs, spec, buf, null)
                val ok = try {
                    writer.cache()
                    true
                } catch (_: Throwable) {
                    false
                }
                if (!ok) break
                offset += len
                delay(150L)
            }
        }
    }

    private fun emitPlaybackTelemetry(
        sid: Long,
        failed: Boolean,
        errorCode: String = "",
        errorMessage: String = "",
        providerDiagnostics: String = ""
    ) {
        if (sid <= 0L || telemetryReportedSessionId == sid) return
        val req = lastRequest ?: return
        telemetryReportedSessionId = sid
        val nowElapsed = SystemClock.elapsedRealtime()
        val startElapsed = loadStartedElapsedMs
        val ttffMs = if (startElapsed > 0L) (nowElapsed - startElapsed).coerceAtLeast(0L) else 0L
        val resolveEnd = resolveCompletedElapsedMs.takeIf { it > 0L } ?: nowElapsed
        val resolveMs = if (startElapsed > 0L) (resolveEnd - startElapsed).coerceAtLeast(0L) else 0L
        val sess = activeStreamSession
        val ep = resolver.extractEpisodeNumber(req.slug).coerceAtLeast(0)
        val title = req.romajiTitle.ifBlank { req.title }.ifBlank { req.slug }
        val versionName = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        resolver.reportPlaybackTelemetry(
            PlaybackTelemetryReport(
                title = title,
                ep = ep,
                animeId = req.anilistId,
                status = PlaybackTelemetryReport.classifyStatus(failed, ttffMs),
                resolveMs = resolveMs,
                ttffMs = ttffMs,
                cacheHit = sess?.cacheHit == true,
                source = sess?.resolvedSourceId.orEmpty(),
                hoster = sess?.resolvedHoster.orEmpty(),
                quality = sess?.activeResolution ?: req.preferredResolution,
                errorCode = errorCode,
                errorMessage = errorMessage,
                providerDiagnostics = providerDiagnostics,
                appVersion = versionName
            )
        )
    }

    private fun stopStreamSession(@Suppress("UNUSED_PARAMETER") syncClose: Boolean = false) {
        val current = activeStreamSession
        activeStreamSession = null
        if (current != null) {
            runCatching { current.closeSession.invoke() }
        }
    }

    fun release() {
        if (released) return
        released = true
        isScrubbing = false
        activeSessionId = -1L
        cancelPrebuffer()
        setVideoBuffering(false)
        loadJob?.cancel()
        loadJob = null
        fullCacheJob?.cancel()
        fullCacheJob = null
        enrichJob?.cancel()
        enrichJob = null
        tickerJob?.cancel()
        tickerJob = null
        stopStreamSession(syncClose = true)
        playerScope.cancel()
        exoPlayer?.setVideoTextureView(null)
        attachedTextureView = null
        exoPlayer?.stop()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
        _playerState.value = PlayerState.Idle
    }

    companion object {
        internal fun reorderFailoverCandidates(
            candidates: List<id.my.milkglass.source.ResolvedMkvMedia>,
            failedUrl: String,
            chosenBackup: id.my.milkglass.source.ResolvedMkvMedia
        ): List<id.my.milkglass.source.ResolvedMkvMedia> =
            listOf(chosenBackup) + candidates.filterNot {
                it.directUrl == failedUrl || it.directUrl == chosenBackup.directUrl
            }

        internal fun selectNextFailoverCandidate(
            candidates: List<id.my.milkglass.source.ResolvedMkvMedia>,
            failedUrl: String,
            preferCompatibleContainer: Boolean
        ): id.my.milkglass.source.ResolvedMkvMedia? {
            val alive = candidates.filter { cand ->
                cand.directUrl != failedUrl &&
                    !id.my.milkglass.source.DeadCandidateCache.isDead(cand.directUrl)
            }
            if (alive.isEmpty()) return null
            return if (preferCompatibleContainer) {
                alive.firstOrNull { it.isHls || it.container == MediaContainer.MP4 || it.container == MediaContainer.HLS }
                    ?: alive.first()
            } else {
                alive.first()
            }
        }
        private const val DEFAULT_USER_AGENT = "Milkglass/1.0 (Android; Media3)"

        private val assTagRegex = Regex("\\u007B[^\\u007D]*\\u007D")
        private val assDrawingLineRegex = Regex(
            "^\\s*m\\s+-?\\d+(?:\\.\\d+)?\\s+-?\\d+(?:\\.\\d+)?(?:\\s+[mlbspcMLBSCP]|\\s+-?\\d+(?:\\.\\d+)?)+\\s*$"
        )

        fun sanitizeSubtitleText(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val stripped = assTagRegex.replace(raw, "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace("\r", "")
            return stripped.lines()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { line -> line.isNotEmpty() && !assDrawingLineRegex.matches(line) }
        }

        @Volatile
        private var sharedStreamCache: SimpleCache? = null

        @Volatile
        private var sharedDbProvider: DatabaseProvider? = null

        const val PREBUFFER_HEAD_BYTES = 2L * 1024L * 1024L
        const val PREBUFFER_METERED_HEAD_BYTES = 512L * 1024L
        private const val PREBUFFER_MKV_TAIL_BYTES = 256L * 1024L
        private const val PREBUFFER_MP4_TAIL_BYTES = 512L * 1024L

        private val _isVideoBuffering = MutableStateFlow(false)
        val isVideoBuffering: StateFlow<Boolean> = _isVideoBuffering.asStateFlow()

        private val activePrebufferWriter = AtomicReference<CacheWriter?>(null)

        internal fun setVideoBuffering(buffering: Boolean) {
            _isVideoBuffering.value = buffering
        PlaybackBandwidthGate.onBufferingChanged(buffering)
        }

        internal fun getSharedStreamCache(context: Context): androidx.media3.datasource.cache.Cache? = getStreamCache(context.applicationContext)

        fun buildStreamCacheKey(
            slug: String,
            activeResolution: String,
            preferCompatibleContainer: Boolean = false
        ): String {
            val mode = if (preferCompatibleContainer) "c" else "s"
            return "${slug.trim().lowercase()}_${activeResolution.trim().lowercase()}_$mode"
        }

        fun cancelPrebuffer() {
            activePrebufferWriter.getAndSet(null)?.let { writer ->
                runCatching { writer.cancel() }
            }
        }

        /**
         * Pre-buffer 2 tahap ke SimpleCache memakai CacheWriter:
         * Tahap 1: 2 MiB awal (header + frame awal) untuk warm start <300ms.
         * Tahap 2: 256 KiB ekor (MKV Cues) atau 512 KiB ekor (MP4 non-faststart moov) bila Content-Length diketahui.
         */
        suspend fun prebufferEpisode(
            context: Context,
            session: LocalStreamSession,
            slug: String,
            preferCompatibleContainer: Boolean = false,
            headBytes: Long = PREBUFFER_HEAD_BYTES
        ): Boolean = withContext(Dispatchers.IO) {
            if (session.isHls || session.localHttpUrl.startsWith("file://", ignoreCase = true)) {
                return@withContext false
            }
            val streamCache = getStreamCache(context.applicationContext) ?: return@withContext false
            val cacheKey = buildStreamCacheKey(slug, session.activeResolution, preferCompatibleContainer)
            val alreadyCached = runCatching {
                streamCache.getCachedBytes(cacheKey, 0L, headBytes)
            }.getOrDefault(0L)
            if (alreadyCached >= headBytes) {
                return@withContext true
            }

            val sharedOkHttp = EngineRuntime.get(context.applicationContext).clients.streamingClient
            val upstreamFactory: DataSource.Factory = if (session.candidates.isNotEmpty()) {
                FailoverHttpDataSource.Factory(
                    client = sharedOkHttp,
                    candidates = session.candidates,
                    defaultUserAgent = session.headers["User-Agent"] ?: DEFAULT_USER_AGENT
                )
            } else {
                OkHttpDataSource.Factory(sharedOkHttp)
                    .setUserAgent(session.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
                    .setDefaultRequestProperties(session.headers)
            }

            val cacheDs = CacheDataSource.Factory()
                .setCache(streamCache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            val targetUri = Uri.parse(session.candidates.firstOrNull()?.directUrl ?: session.localHttpUrl)
            val headSpec = DataSpec.Builder()
                .setUri(targetUri)
                .setPosition(0L)
                .setLength(headBytes)
                .setKey(cacheKey)
                .build()

            var knownTotalLength = C.LENGTH_UNSET.toLong()
            val headWriter = CacheWriter(cacheDs, headSpec, ByteArray(64 * 1024)) { reqLen, _, _ ->
                if (reqLen > 0L) knownTotalLength = reqLen
            }
            cancelPrebuffer()
            activePrebufferWriter.set(headWriter)
            val headOk = try {
                headWriter.cache()
                true
            } catch (_: Throwable) {
                false
            } finally {
                activePrebufferWriter.compareAndSet(headWriter, null)
            }
            if (!headOk) return@withContext false

            val totalLen = knownTotalLength.takeIf { it > 0L } ?: runCatching {
                androidx.media3.datasource.cache.ContentMetadata.getContentLength(streamCache.getContentMetadata(cacheKey))
            }.getOrDefault(C.LENGTH_UNSET.toLong())

            val isMp4 = session.candidates.firstOrNull()?.container == MediaContainer.MP4
            val tailBytes = if (isMp4) PREBUFFER_MP4_TAIL_BYTES else PREBUFFER_MKV_TAIL_BYTES
            if (totalLen > headBytes + tailBytes) {
                val tailStart = totalLen - tailBytes
                val tailCached = runCatching {
                    streamCache.getCachedBytes(cacheKey, tailStart, tailBytes)
                }.getOrDefault(0L)
                if (tailCached < tailBytes) {
                    val tailSpec = DataSpec.Builder()
                        .setUri(targetUri)
                        .setPosition(tailStart)
                        .setLength(tailBytes)
                        .setKey(cacheKey)
                        .build()
                    val tailWriter = CacheWriter(cacheDs, tailSpec, ByteArray(64 * 1024), null)
                    activePrebufferWriter.set(tailWriter)
                    try {
                        tailWriter.cache()
                    } catch (_: Throwable) {
                    } finally {
                        activePrebufferWriter.compareAndSet(tailWriter, null)
                    }
                }
            }
            true
        }

        private const val MIN_FREE_DISK_FOR_FULL_CACHE_BYTES = 512L * 1024L * 1024L

        fun computeAdaptiveCacheBytes(availableDiskBytes: Long): Long {
            val minBytes = 128L * 1024L * 1024L
            val maxBytes = 1024L * 1024L * 1024L
            return (availableDiskBytes / 10L).coerceIn(minBytes, maxBytes)
        }

        fun evictStreamCacheKeys(context: Context, keys: Collection<String>) {
            val streamCache = getStreamCache(context.applicationContext) ?: return
            for (k in keys) {
                runCatching { streamCache.removeResource(k) }
            }
        }

        fun getStreamCache(context: Context): Cache? {
            sharedStreamCache?.let { return it }
            return synchronized(this) {
                sharedStreamCache ?: runCatching {
                    val dir = File(context.applicationContext.cacheDir, "mkv_stream_cache")
                    if (!dir.exists()) dir.mkdirs()
                    val availableBytes = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(2560L * 1024L * 1024L)
                    val adaptiveMaxBytes = computeAdaptiveCacheBytes(availableBytes)
                    SimpleCache(
                        dir,
                        LeastRecentlyUsedCacheEvictor(adaptiveMaxBytes),
                        getDatabaseProvider(context.applicationContext)
                    )
                }.getOrNull()?.also { sharedStreamCache = it }
            }
        }

        fun getDatabaseProvider(context: Context): DatabaseProvider {
            return sharedDbProvider ?: synchronized(this) {
                sharedDbProvider ?: StandaloneDatabaseProvider(context.applicationContext).also {
                    sharedDbProvider = it
                }
            }
        }
    }
}

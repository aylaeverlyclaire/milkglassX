package id.my.milkglass.player

import android.content.Context
import android.net.Uri
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
import androidx.media3.datasource.cache.SimpleCache
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
import id.my.milkglass.resolver.LocalStreamSession
import id.my.milkglass.resolver.ResolvedStream
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
    val startPositionMs: Long = 0L
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

object MPVLibBridge {
    private val mpvClass: Class<*>? by lazy {
        runCatching { Class.forName("is.xyz.mpv.MPVLib") }.getOrNull()
    }

    val isAvailable: Boolean
        get() = mpvClass != null

    fun setOptionString(name: String, value: String) {
        val clazz = mpvClass ?: return
        runCatching {
            val method = clazz.getMethod("setOptionString", String::class.java, String::class.java)
            method.invoke(null, name, value)
        }
    }

    fun command(cmd: Array<String>) {
        val clazz = mpvClass ?: return
        runCatching {
            val method = clazz.getMethod("command", Array<String>::class.java)
            method.invoke(null, cmd as Any)
        }
    }
}

@OptIn(UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val backendBaseUrl: String = StreamResolver.DEFAULT_BACKEND_BASE_URL,
    private val masterAppSecret: ByteArray = ByteArray(0),
    private val authTokenProvider: () -> String? = { null },
    private val resolver: StreamResolver = if (backendBaseUrl == StreamResolver.DEFAULT_BACKEND_BASE_URL) {
        EngineRuntime.get(context).streamResolver
    } else {
        StreamResolver(
            context = context,
            backendBaseUrl = backendBaseUrl,
            masterAppSecret = masterAppSecret,
            authTokenProvider = authTokenProvider
        )
    }
) {
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var attachedTextureView: TextureView? = null

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeStreamSession: LocalStreamSession? = null
    private var loadJob: Job? = null
    private var tickerJob: Job? = null
    private var lastRequest: PlaybackRequest? = null

    private val sessionCounter = AtomicLong(0L)
    @Volatile private var activeSessionId: Long = 0L
    @Volatile private var released = false

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

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
                    _playerState.value = PlayerState.Buffering
                    _snapshot.update { it.copy(phase = PlaybackPhase.LOADING, error = null) }
                }
                Player.STATE_READY -> {
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
            val friendly = ErrorMapper.mapExoPlayerError(error, context)
            _playerState.value = PlayerState.Error(friendly)
            _snapshot.update { it.copy(phase = PlaybackPhase.ERROR, error = friendly) }
        }
    }

    fun initialize() {
        if (released || exoPlayer != null) return

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(context, videoTrackSelectionFactory).apply {
            setParameters(
                buildUponParameters()
                    .clearVideoSizeConstraints()
                    .setPreferredTextLanguages("id", "ind", "en", "eng")
                    .setPreferredAudioLanguages("ja", "jpn")
            )
        }

        // Mulai putar secepat mungkin (0,5 dtk data cukup), tetapi batasi memori: remux 1080p berbitrate tinggi
        // dengan "prioritaskan waktu" bisa menumpuk >100 MB di heap dan membuat perangkat RAM kecil macet/OOM.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            // Simpan 30 dtk ke belakang: geser mundur / "ulang 10 dtk" langsung dari buffer tanpa muat ulang.
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()

        // MediaCodec perangkat dulu (hemat baterai); bila codec tidak didukung perangkat, jatuh ke dekoder FFmpeg
        // (DTS, TrueHD, EAC3, Vorbis, ALAC, dll.). Decoder fallback: coba dekoder lain bila yang pertama gagal init.
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player ->
                // Geser ke keyframe terdekat: seek jauh lebih cepat daripada decode maju sampai frame persis.
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

    fun load(request: PlaybackRequest) {
        if (released) return
        if (exoPlayer == null) initialize()

        val sid = sessionCounter.incrementAndGet()
        activeSessionId = sid
        lastRequest = request

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
                    preferredRes = if (request.preferredResolution == "Auto") "1080p" else request.preferredResolution,
                    title = request.title,
                    romajiTitle = request.romajiTitle,
                    anilistId = request.anilistId,
                    scope = playerScope
                )
                if (released || !isActive || activeSessionId != sid) {
                    session.closeSession()
                    return@launch
                }

                activeStreamSession = session
                val availRes = listOf("Auto") + session.availableResolutions
                _availableResolutions.value = availRes
                _snapshot.update {
                    if (it.sessionId != sid) it else it.copy(
                        activeResolution = session.activeResolution,
                        availableResolutions = availRes,
                        resolvedSourceId = session.resolvedSourceId,
                        resolvedHoster = session.resolvedHoster
                    )
                }

                if (MPVLibBridge.isAvailable) {
                    configureMpvForSequentialPipe(session.localHttpUrl)
                }
                prepareMedia(
                    mediaUrl = session.localHttpUrl,
                    headers = emptyMap(),
                    startPositionMs = request.startPositionMs,
                    cacheKey = "${request.slug}_${session.activeResolution}"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (released || activeSessionId != sid) return@launch
                val friendly = ErrorMapper.mapResolverError(e, context)
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
        val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
        load(
            current.copy(
                preferredResolution = newResolution,
                startPositionMs = resumeMs
            )
        )
    }

    fun retry() {
        val current = lastRequest ?: return
        val resumeMs = getCurrentPosition().takeIf { it > 0L } ?: _snapshot.value.positionMs
        load(current.copy(startPositionMs = resumeMs))
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
        prepareMedia(resolvedStream.directUrl, resolvedStream.headers, startPositionMs, cacheKey)
    }

    private fun prepareMedia(
        mediaUrl: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        cacheKey: String?
    ) {
        val playerInstance = exoPlayer ?: return
        val sharedOkHttp = EngineRuntime.get(context).clients.mediaAndApiClient
        val httpFactory = OkHttpDataSource.Factory(sharedOkHttp)
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(headers)

        val upstreamFactory = DefaultDataSource.Factory(context, httpFactory)
        val isLocalFile = mediaUrl.startsWith("file://", ignoreCase = true)
        val finalFactory: DataSource.Factory = if (!isLocalFile) {
            val streamCache = getStreamCache(context)
            if (streamCache != null) {
                CacheDataSource.Factory()
                    .setCache(streamCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                upstreamFactory
            }
        } else {
            upstreamFactory
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(mediaUrl))
            .setMimeType(mimeTypeFor(mediaUrl))
        if (!cacheKey.isNullOrBlank() && !isLocalFile) {
            mediaItemBuilder.setCustomCacheKey(cacheKey)
        }

        val mediaSource = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(finalFactory)
            .createMediaSource(mediaItemBuilder.build())

        playerInstance.setMediaSource(mediaSource)
        if (startPositionMs > 0L) {
            playerInstance.seekTo(startPositionMs)
        }
        playerInstance.prepare()
        playerInstance.playWhenReady = true
    }

    private fun startPositionTicker() {
        tickerJob?.cancel()
        tickerJob = playerScope.launch {
            while (isActive && !released) {
                delay(250L)
                val p = exoPlayer ?: continue
                if (_snapshot.value.isReady) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: _snapshot.value.durationMs
                    val buf = p.bufferedPosition.coerceAtLeast(0L)
                    _snapshot.update { cur ->
                        if (cur.sessionId != activeSessionId || !cur.isReady) cur
                        else cur.copy(positionMs = pos, durationMs = dur, bufferedPositionMs = buf)
                    }
                }
            }
        }
    }

    private fun configureMpvForSequentialPipe(localStreamUrl: String) {
        MPVLibBridge.setOptionString("hwdec", "auto")
        MPVLibBridge.setOptionString("demuxer-lavf-o", "reconnect=1,reconnect_streamed=1")
        MPVLibBridge.setOptionString("force-seekable", "yes")
        MPVLibBridge.setOptionString("demuxer-max-bytes", "150MiB")
        MPVLibBridge.setOptionString("demuxer-max-back-bytes", "75MiB")
        MPVLibBridge.setOptionString("sub-ass-override", "force")
        MPVLibBridge.setOptionString("alang", "jpn,ja")
        MPVLibBridge.setOptionString("slang", "id,ind,en,eng")
        MPVLibBridge.setOptionString("sid", "auto")
        MPVLibBridge.command(arrayOf("loadfile", localStreamUrl, "replace"))
    }

    fun setMaxVideoResolution(maxHeight: Int) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
        if (maxHeight <= 0) params.clearVideoSizeConstraints()
        else params.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        selector.setParameters(params)
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

    fun stopStreamSession() {
        activeStreamSession?.closeSession?.invoke()
        activeStreamSession = null
    }

    fun release() {
        if (released) return
        released = true
        activeSessionId = -1L
        loadJob?.cancel()
        loadJob = null
        tickerJob?.cancel()
        tickerJob = null
        playerScope.cancel()
        exoPlayer?.setVideoTextureView(null)
        attachedTextureView = null
        exoPlayer?.stop()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
        stopStreamSession()
        _playerState.value = PlayerState.Idle
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "Milkglass/1.0 (Android; Media3)"

        internal const val MIN_BUFFER_MS = 15_000
        internal const val MAX_BUFFER_MS = 50_000
        internal const val BUFFER_FOR_PLAYBACK_MS = 500
        internal const val BUFFER_AFTER_REBUFFER_MS = 1_500
        internal const val BACK_BUFFER_MS = 30_000
        internal const val TARGET_BUFFER_BYTES = 96 * 1024 * 1024

        /**
         * Tebakan MIME untuk urutan extractor. Hanya memengaruhi URUTAN percobaan (extractor lain tetap dicoba lewat
         * sniffing), jadi menebak MKV untuk URL tanpa ekstensi aman dan menghindari sniff MP4 yang sia-sia.
         */
        internal fun mimeTypeFor(url: String): String {
            val lower = url.lowercase()
            val path = lower.substringBefore('#').substringBefore('?')
            return when {
                ".m3u8" in lower -> MimeTypes.APPLICATION_M3U8
                ".mkv" in lower || path.endsWith(".mka") -> MimeTypes.VIDEO_MATROSKA
                path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
                path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
                else -> MimeTypes.VIDEO_MATROSKA
            }
        }

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

        fun getStreamCache(context: Context): Cache? {
            sharedStreamCache?.let { return it }
            return synchronized(this) {
                sharedStreamCache ?: runCatching {
                    val dir = File(context.applicationContext.cacheDir, "mkv_stream_cache")
                    if (!dir.exists()) dir.mkdirs()
                    SimpleCache(
                        dir,
                        LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L),
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

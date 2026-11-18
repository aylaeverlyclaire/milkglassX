package id.my.milkglass.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import id.my.milkglass.resolver.LocalStreamSession
import id.my.milkglass.resolver.ResolvedStream
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.resolver.StreamSourceDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

sealed class PlayerState {
    object Idle : PlayerState()
    object Buffering : PlayerState()
    data class Playing(val isPlaying: Boolean) : PlayerState()
    object Paused : PlayerState()
    object Ended : PlayerState()
    data class Error(val error: FriendlyPlayerError) : PlayerState()
}

object MPVLibBridge {
    fun setOptionString(name: String, value: String) {
        runCatching {
            val clazz = Class.forName("is.xyz.mpv.MPVLib")
            val method = clazz.getMethod("setOptionString", String::class.java, String::class.java)
            method.invoke(null, name, value)
        }
    }

    fun command(cmd: Array<String>) {
        runCatching {
            val clazz = Class.forName("is.xyz.mpv.MPVLib")
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
    private val resolver: StreamResolver = StreamResolver(
        context = context,
        backendBaseUrl = backendBaseUrl,
        masterAppSecret = masterAppSecret,
        authTokenProvider = authTokenProvider
    )
) {
    private var exoPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeStreamSession: LocalStreamSession? = null

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _availableResolutions = MutableStateFlow<List<String>>(listOf("Auto", "1080p", "720p", "480p"))
    val availableResolutions: StateFlow<List<String>> = _availableResolutions.asStateFlow()

    val player: ExoPlayer?
        get() = exoPlayer

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> _playerState.value = PlayerState.Idle
                Player.STATE_BUFFERING -> _playerState.value = PlayerState.Buffering
                Player.STATE_READY -> {
                    val isPlaying = exoPlayer?.isPlaying ?: false
                    _playerState.value = if (isPlaying) PlayerState.Playing(true) else PlayerState.Paused
                }
                Player.STATE_ENDED -> _playerState.value = PlayerState.Ended
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (exoPlayer?.playbackState == Player.STATE_READY) {
                _playerState.value = if (isPlaying) PlayerState.Playing(true) else PlayerState.Paused
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val friendlyError = ErrorMapper.mapExoPlayerError(error, context)
            _playerState.value = PlayerState.Error(friendlyError)
        }
    }

    fun initialize() {
        if (exoPlayer != null) return

        val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()
        val videoTrackSelectionFactory = AdaptiveTrackSelection.Factory()
        trackSelector = DefaultTrackSelector(context, videoTrackSelectionFactory).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSizeSd()
                    .clearVideoSizeConstraints()
                    .setPreferredTextLanguage("id")
                    .setPreferredAudioLanguage("ja")
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                60_000,
                2_500,
                5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

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
            .also {
                it.addListener(playerListener)
            }
    }

    fun playStream(
        resolvedStream: ResolvedStream,
        subtitleUrl: String? = null,
        startPositionMs: Long = 0L
    ) {
        if (exoPlayer == null) initialize()

        val playerInstance = exoPlayer ?: return

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(resolvedStream.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(resolvedStream.headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(resolvedStream.directUrl))

        val urlLower = resolvedStream.directUrl.lowercase()
        when {
            urlLower.contains(".m3u8") -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            urlLower.endsWith(".mkv") -> mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
            else -> mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
        }

        if (!subtitleUrl.isNullOrBlank()) {
            val subMimeType = when {
                subtitleUrl.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
                subtitleUrl.endsWith(".ass", ignoreCase = true) ||
                subtitleUrl.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                .setMimeType(subMimeType)
                .setLanguage("id")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

            mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))
        }

        val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

        playerInstance.setMediaSource(mediaSource)
        if (startPositionMs > 0) {
            playerInstance.seekTo(startPositionMs)
        }
        playerInstance.prepare()
        playerInstance.playWhenReady = true
    }

    
    fun playStreamEpisode(
        slug: String,
        preferredResolution: String = "1080p",
        anilistId: Int? = null,
        title: String? = null,
        romajiTitle: String? = null,
        startPositionMs: Long = 0L,
        onReady: (localUrl: String) -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        stopStreamSession()
        _playerState.value = PlayerState.Buffering

        playerScope.launch {
            try {
                val session = resolver.resolveAndStartStream(
                    slug = slug,
                    preferredRes = preferredResolution,
                    scope = playerScope,
                    anilistId = anilistId,
                    title = title.orEmpty(),
                    romajiTitle = romajiTitle.orEmpty()
                )
                activeStreamSession = session
                _availableResolutions.value = listOf("Auto") + session.availableResolutions

                
                configureMpvForSequentialPipe(session.localHttpUrl)

                
                val dummySource = StreamSourceDto(
                    id = 0,
                    episodeId = 0,
                    type = "direct",
                    quality = when {
                        session.activeResolution.contains("1080") -> 4
                        session.activeResolution.contains("720") -> 3
                        else -> 2
                    },
                    urlPayload = session.localHttpUrl
                )
                playStream(
                    resolvedStream = ResolvedStream(
                        source = dummySource,
                        directUrl = session.localHttpUrl,
                        headers = emptyMap()
                    ),
                    startPositionMs = startPositionMs
                )
                onReady(session.localHttpUrl)
            } catch (e: Throwable) {
                val friendly = ErrorMapper.mapResolverError(e, context)
                _playerState.value = PlayerState.Error(friendly)
                onError(e)
            }
        }
    }

    private fun configureMpvForSequentialPipe(localStreamUrl: String) {
        MPVLibBridge.setOptionString("hwdec", "auto")
        MPVLibBridge.setOptionString("demuxer-lavf-o", "reconnect=1,reconnect_streamed=1")
        MPVLibBridge.setOptionString("force-seekable", "yes")
        MPVLibBridge.setOptionString("demuxer-max-bytes", "150MiB")
        MPVLibBridge.setOptionString("demuxer-max-back-bytes", "75MiB")
        MPVLibBridge.setOptionString("sub-ass-override", "no")
        MPVLibBridge.setOptionString("alang", "jpn,ja")
        MPVLibBridge.setOptionString("slang", "id,ind,en,eng")
        MPVLibBridge.setOptionString("sid", "auto")

        MPVLibBridge.command(arrayOf("loadfile", localStreamUrl, "replace"))
    }

    fun setMaxVideoResolution(maxHeight: Int) {
        val selector = trackSelector ?: return
        if (maxHeight <= 0) {
            selector.setParameters(
                selector.buildUponParameters().clearVideoSizeConstraints()
            )
        } else {
            selector.setParameters(
                selector.buildUponParameters().setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            )
        }
    }

    fun selectVideoTrack(groupIndex: Int, trackIndex: Int) {
        val playerInstance = exoPlayer ?: return
        val selector = trackSelector ?: return
        val trackGroups = playerInstance.currentTracks.groups
        if (groupIndex in trackGroups.indices) {
            val mediaTrackGroup = trackGroups[groupIndex].mediaTrackGroup
            val override = TrackSelectionOverride(mediaTrackGroup, trackIndex)
            selector.setParameters(
                selector.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .addOverride(override)
            )
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
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
        stopStreamSession()
        exoPlayer?.removeListener(playerListener)
        exoPlayer?.release()
        exoPlayer = null
        trackSelector = null
        _playerState.value = PlayerState.Idle
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        @Volatile
        private var databaseProviderInstance: DatabaseProvider? = null

        fun getDatabaseProvider(context: Context): DatabaseProvider {
            return databaseProviderInstance ?: synchronized(this) {
                databaseProviderInstance ?: StandaloneDatabaseProvider(context.applicationContext).also {
                    databaseProviderInstance = it
                }
            }
        }

        

        
    }
}

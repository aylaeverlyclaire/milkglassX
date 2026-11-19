package com.milkglass.app.ui.screens

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.milkglass.app.core.app.SkipKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.milkglass.app.BuildConfig
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.PlayerEngine
import com.milkglass.app.core.app.ViewEntry
import com.milkglass.app.core.design.CssTransparent
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkEasing
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.cssLinearGradient
import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.core.poster.PosterArt
import com.milkglass.app.core.remote.DEFAULT_CATALOG_BASE_URL
import com.milkglass.app.ui.common.BadgeKind
import com.milkglass.app.ui.common.ButtonVariant
import com.milkglass.app.ui.common.Gutter
import com.milkglass.app.ui.common.MilkBadge
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.SectionHeader
import com.milkglass.app.ui.common.milkClickable
import id.my.milkglass.EngineRuntime
import id.my.milkglass.player.PlayerManager
import id.my.milkglass.player.PlaybackPhase
import id.my.milkglass.player.PlaybackRequest
import id.my.milkglass.player.PlayerSnapshot
import id.my.milkglass.player.PlayerState
import com.milkglass.app.ui.components.CloudflareVerificationOverlay
import id.my.milkglass.net.CloudflareChallengeCoordinator
import id.my.milkglass.download.MkvDownloadManager
import id.my.milkglass.download.MkvDownloadRequest
import id.my.milkglass.download.MkvDownloadStatus
import id.my.milkglass.resolver.StreamResolver
import id.my.milkglass.source.MkvQuality
import kotlinx.coroutines.delay
import kotlin.math.ceil

@Composable
fun PlayerScreen(ctl: AppController, entry: ViewEntry.Player) {
    val e = entry.engine
    val c = Milk.colors
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val playerManager = remember(context) {
        PlayerManager(
            context = context.applicationContext,
            backendBaseUrl = DEFAULT_CATALOG_BASE_URL,
            masterAppSecret = BuildConfig.MILKGLASS_MASTER_SECRET.toByteArray(Charsets.UTF_8),
            authTokenProvider = { ctl.persisted.accessToken }
        ).also { it.initialize() }
    }
    val snap by playerManager.snapshot.collectAsState()
    var cfChallenge by remember { mutableStateOf<Pair<String, String>?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(playerManager) {
        CloudflareChallengeCoordinator.uiLauncher = { host, url ->
            mainHandler.post { cfChallenge = host to url }
        }
        onDispose {
            CloudflareChallengeCoordinator.uiLauncher = null
            ctl.onQualityChanged = null
            playerManager.release()
        }
    }

    DisposableEffect(e, e.episode, playerManager) {
        val slug = StreamResolver.buildEpisodeSlug(e.anime.romaji.ifBlank { e.anime.title }, e.episode)
        e.onNativeSeekMs = { ms -> playerManager.seekTo(ms) }
        e.onNativePlayPause = { play -> if (play) playerManager.resume() else playerManager.pause() }
        e.onNativeRetry = { playerManager.retry() }
        ctl.onQualityChanged = { newQuality -> playerManager.switchResolution(newQuality) }

        playerManager.load(
            PlaybackRequest(
                slug = slug,
                preferredResolution = ctl.persisted.settings.quality,
                anilistId = e.anime.id,
                title = e.anime.title,
                romajiTitle = e.anime.romaji,
                startPositionMs = (e.position * 1000.0).toLong().coerceAtLeast(0L)
            )
        )

        onDispose {
            e.onNativeSeekMs = null
            e.onNativePlayPause = null
            e.onNativeRetry = null
            e.nativeStreamActive = false
            e.nativeBuffering = false
        }
    }

    LaunchedEffect(snap) {
        e.nativeStreamActive = snap.isReady
        e.nativeBuffering = snap.isLoading
        e.isPlaying = snap.isPlaying
        e.bufferTimer = if (snap.isLoading) 1.0 else 0.0
        if (snap.durationMs > 0L) {
            e.nativeDurationSeconds = (snap.durationMs / 1000L).toInt().coerceAtLeast(1)
        } else {
            e.nativeDurationSeconds = null
        }
        if (snap.isReady && snap.durationMs > 0L) {
            e.syncFromNativePlayer(snap.positionMs)
        }
        if (snap.phase == PlaybackPhase.ENDED && snap.durationMs > 0L) {
            e.position = (snap.durationMs / 1000.0)
        }
    }

    LaunchedEffect(e) { ctl.loadSkipTimes(e) }
    LaunchedEffect(e.anime.id) { ctl.loadEpisodeMeta(e.anime) }
    LaunchedEffect(snap.isReady, e.anime.id, e.episode) {
        if (snap.isReady && ctl.online) {
            ctl.nextPlayableEpisode(e.anime, e.episode)?.let { nextEp ->
                EngineRuntime.get(context.applicationContext).prefetchEpisode(e.anime.title, e.anime.romaji, nextEp)
            }
        }
    }
    
    LaunchedEffect(e, playerManager) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(250)

                e.tick()
            }
        }
    }
    
    FullscreenOrientationEffect(e.isFullscreen)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface)) {
        val vh = maxHeight
        if (e.isFullscreen) {
            VideoArea(ctl, e, playerManager, Modifier.fillMaxSize(), fullscreen = true)
        } else {
            Column(Modifier.fillMaxSize()) {
                
                Box(Modifier.fillMaxWidth().background(Color.Black).windowInsetsPadding(WindowInsets.statusBars))
                VideoArea(ctl, e, playerManager, Modifier.fillMaxWidth().aspectRatio(16f / 9f).heightIn(max = vh * .56f), fullscreen = false)
                val errObj = snap.error
                if (snap.phase == PlaybackPhase.ERROR && errObj != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2B1215))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MilkText(
                            errObj.userMessage,
                            Milk.type.style(12f, 500, 16f),
                            Color(0xFFFFB4AB),
                            Modifier.weight(1f).padding(end = 12.dp)
                        )
                        MilkButton("Coba Lagi", onClick = { playerManager.retry() }, variant = ButtonVariant.Outlined, small = true)
                    }
                }
                PlayerInfo(ctl, entry, Modifier.weight(1f))
            }
        }

        val pendingCf = cfChallenge
        if (pendingCf != null) {
            CloudflareVerificationOverlay(
                host = pendingCf.first,
                targetUrl = pendingCf.second,
                onDismiss = { cfChallenge = null }
            )
        }
    }
}

@Composable
private fun VideoButton(icon: Int, size: Int, onClick: () -> Unit, modifier: Modifier = Modifier, bg: Boolean = true, mirrored: Boolean = false, label: String? = null, enabled: Boolean = true, guard: Boolean = true) {
    Box(
        modifier.size(size.dp).clip(CircleShape).then(if (bg) Modifier.background(Color.Black.copy(alpha = .45f)) else Modifier)
            .milkClickable(enabled = enabled, pressDim = false, guard = guard, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = when { size >= 64 -> 36.dp; size == 40 -> 28.dp; else -> 28.dp }
        Box(Modifier.graphicsLayer { scaleX = if (mirrored) -1f else 1f }) { MilkIcon(icon, size = iconSize, tint = Color.White) }
        if (label != null) {
            MilkText(label, Milk.type.style(9f, 700, 9f), Color.White, Modifier.offset(y = 2.dp))
        }
    }
}

@Composable
private fun VideoArea(ctl: AppController, e: PlayerEngine, playerManager: PlayerManager, modifier: Modifier, fullscreen: Boolean) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val a = e.anime
    val from = parseCssColor(a.colorFrom) ?: Color.Black
    val to = parseCssColor(a.colorTo) ?: Color.Black
    var fx by remember { mutableStateOf(SeekFx(0, 0, 0)) }
    var fxOn by remember { mutableStateOf(false) }
    LaunchedEffect(fx.token) { if (fx.token > 0) { delay(SEEK_FX_MS); fxOn = false } }
    
    
    fun seekWithFx(dir: Int, keepControls: Boolean) {
        val visible = !e.faded
        e.seekBy(10.0 * dir)
        if (keepControls) e.setControls(visible)
        fx = SeekFx(dir, if (fxOn && fx.dir == dir) fx.total + 10 else 10, fx.token + 1)
        fxOn = true
    }
    
    val alpha by animateFloatAsState(if (e.faded) 0f else 1f, tween(if (e.faded) 200 else 120, easing = MilkEasing.Ease), label = "controls")
    val cutout = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)) else Modifier
    val buffering = e.nativeBuffering && !e.isOffline
    val bufferingActive = e.nativeBuffering || e.isOffline

    Box(
        
        
        modifier.clipToBounds().background(cssLinearGradient((120 + e.episode * 17).toFloat(), listOf(from, to)))
            .pointerInput(e) {
                
                
                
                var lastTapAt = 0L
                var visibleBeforeTap = true
                var seekModeUntil = 0L
                detectTapGestures { off ->
                    val t = SystemClock.uptimeMillis()
                    val dir = if (off.x < size.width / 2f) -1 else 1
                    if (t < seekModeUntil) {
                        seekWithFx(dir, true); seekModeUntil = t + SEEK_MODE_MS; lastTapAt = 0L
                    } else if (lastTapAt != 0L && t - lastTapAt <= DOUBLE_TAP_MS) {
                        e.setControls(visibleBeforeTap)
                        seekWithFx(dir, true); seekModeUntil = t + SEEK_MODE_MS; lastTapAt = 0L
                    } else {
                        lastTapAt = t; visibleBeforeTap = !e.faded; e.setControls(e.faded)
                    }
                }
            },
    ) {
        
        
        val subLines by playerManager.subtitleLines.collectAsState()
        val videoAspect by playerManager.videoAspectRatio.collectAsState()
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (e.nativeStreamActive) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).also { tv ->
                            playerManager.attachTextureView(tv)
                        }
                    },
                    update = { tv ->
                        playerManager.attachTextureView(tv)
                    },
                    modifier = Modifier.fillMaxHeight().aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
                )
            }
        }
        if (e.nativeStreamActive && subLines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (fullscreen) 28.dp else 10.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                subLines.takeLast(3).forEach { line ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.52f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        MilkText(
                            text = line,
                            style = ty.style(if (fullscreen) 16f else 12.5f, 600, if (fullscreen) 21f else 17f)
                                .copy(shadow = Shadow(Color.Black, androidx.compose.ui.geometry.Offset(0f, 1.5f), 4f)),
                            color = Color.White,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(80.dp).graphicsLayer { this.alpha = alpha }
                .background(cssLinearGradient(180f, listOf(Color.Black.copy(alpha = .4f), CssTransparent))),
        )

        
        Box(Modifier.fillMaxSize().then(cutout).graphicsLayer { this.alpha = alpha }) {
            Row(
                Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoButton(MilkIcons.Rst, 48, { seekWithFx(-1, false) }, label = "10", enabled = !e.faded, guard = false)
                Box(Modifier.graphicsLayer { this.alpha = if (bufferingActive) 0f else 1f }) {
                    VideoButton(if (e.isPlaying) MilkIcons.Pause else MilkIcons.Play, 64, { e.togglePlayPause() }, enabled = !e.faded && !bufferingActive, guard = false)
                }
                VideoButton(MilkIcons.Rst, 48, { seekWithFx(1, false) }, label = "10", mirrored = true, enabled = !e.faded, guard = false)
            }
            Row(
                Modifier.fillMaxWidth().then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoButton(MilkIcons.Back, 40, { ctl.goBack() }, bg = false, enabled = !e.faded)
                if (fullscreen) {
                    
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        MilkText(
                            ctl.episodeLineOf(a, e.episode),
                            ty.style(16f, 700), Color.White, maxLines = 1, ellipsize = true,
                        )
                        MilkText(a.romaji, ty.style(12f), Color.White.copy(alpha = .8f), maxLines = 1, ellipsize = true)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        
        if (buffering) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                BufferSpinner()
                MilkText(
                    tr.t("player.loading"),
                    ty.style(12f).copy(shadow = Shadow(Color.Black.copy(alpha = .6f), androidx.compose.ui.geometry.Offset(0f, 1f), 6f)),
                    Color.White,
                )
            }
        }

        
        AnimatedVisibility(
            visible = fxOn,
            modifier = Modifier.align(if (fx.dir < 0) Alignment.CenterStart else Alignment.CenterEnd).then(cutout)
                .padding(horizontal = if (fullscreen) 64.dp else 8.dp),
            enter = fadeIn(tween(80)), exit = fadeOut(tween(200)),
        ) { SeekIndicator(fx.dir, fx.total) }

        
        val skipKind = e.skipButtonKind
        if (skipKind != null && !(ctl.features.autoSkip && ctl.persisted.settings.autoSkipOpeningEnding != 0) && e.adSecondsLeft <= 0) {
            Box(
                Modifier.align(Alignment.BottomEnd).then(cutout).padding(end = 12.dp, bottom = 56.dp).height(36.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .92f))
                    .milkClickable(pressDim = false) { e.skip() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                MilkText(if (skipKind == SkipKind.Ending) tr.t("player.skipEnding") else tr.t("player.skipOpening"), ty.skipButton, Color(0xFF1D1B20))
            }
        }

        
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(cssLinearGradient(180f, listOf(CssTransparent, Color.Black.copy(alpha = .75f))))
                .pointerInput(e.faded) { if (!e.faded) detectTapGestures { } }, 
        ) {
            Row(Modifier.then(cutout).padding(start = 12.dp, end = 12.dp).offset(y = 24.dp).fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                val durLabel = if (e.duration > 0) ctl.time.formatDuration(e.duration.toDouble()) else "--:--"
                MilkText("${ctl.time.formatDuration(if (e.duration > 0) e.position else 0.0)} / $durLabel", ty.style(12f, 400, mono = true), Color.White)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = .18f))
                        .milkClickable(enabled = !e.faded, pressDim = false) { ctl.overlay = com.milkglass.app.core.app.Overlay.QualityPicker }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MilkText(ctl.effectiveQuality(), ty.style(12f, 600), Color.White)
                }
                Box(
                    Modifier.size(36.dp, 40.dp).clip(RoundedCornerShape(20.dp)).milkClickable(enabled = !e.faded, pressDim = false) { e.toggleFullscreen() },
                    contentAlignment = Alignment.Center,
                ) { MilkIcon(MilkIcons.Fs, size = 20.dp, tint = Color.White) }
            }
            SeekBar(e.position, e.duration, fullscreen, { e.seekTo(it) }, Modifier.then(cutout).padding(horizontal = 8.dp).offset(y = 60.dp).fillMaxWidth())
        }

        
        if (fullscreen && e.duration > 0) {
            val frac = (e.position / e.duration).toFloat().coerceIn(0f, 1f)
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                    .graphicsLayer { this.alpha = 1f - alpha }
                    .background(Color.White.copy(alpha = .3f)),
            ) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.primary))
            }
        }

        
        
        if (e.inEnding && !e.hasEnded && e.noticeTimer <= 0 && !e.isOffline) {
            val upNext = ctl.nextPlayableEpisode(a, e.episode)
            if (upNext != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).then(cutout).padding(end = 16.dp, bottom = if (skipKind != null) 104.dp else 56.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .55f))
                        .milkClickable { e.playNextNow() }.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    MilkText(ctl.nextUpLineOf(a, upNext), ty.style(13f, 500), Color.White)
                }
            }
        }
        if (e.isOffline) {
            PlayerOverlay(Modifier.padding(top = 48.dp)) {
                MilkBadge(tr.t("player.offline"), BadgeKind.Neutral)
                MilkText(tr.t("player.noConnection"), ty.style(14f, 700), Color.White)
                MilkButton(tr.t("common.retry"), { e.retry() }, Modifier.widthIn(min = 140.dp), variant = ButtonVariant.Primary, onHero = true)
            }
        } else if (e.noticeTimer > 0) {
            val nextEp = e.episode + 1
            PlayerOverlay(Modifier, fireworks = true) {
                MilkText(tr.t("player.next"), ty.style(13f), Color.White.copy(alpha = .85f))
                MilkText(ctl.episodeLineOf(a, nextEp), ty.style(14f, 700), Color.White)
                MilkText(tr.t("player.playingInSeconds", "ceil" to ceil(e.noticeTimer).toInt()), ty.style(13f), Color.White.copy(alpha = .85f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MilkButton(tr.t("player.playNow"), { e.playNextNow() }, Modifier.widthIn(min = 140.dp), variant = ButtonVariant.Primary, onHero = true)
                    MilkButton(tr.t("common.cancel"), { e.cancelAutoNext() }, Modifier.widthIn(min = 140.dp), onHero = true)
                }
            }
        } else if (e.hasEnded) {
            PlayerOverlay(Modifier, fireworks = true) {
                MilkText(if (e.episode >= a.totalEpisodes) tr.t("player.finishedThanks") else tr.t("player.episodeFinished"), ty.style(14f, 700), Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MilkButton(tr.t("player.replay"), { e.replay() }, Modifier.widthIn(min = 140.dp), variant = ButtonVariant.Primary, onHero = true)
                    MilkButton(tr.t("common.back"), { ctl.goBack() }, Modifier.widthIn(min = 140.dp), onHero = true)
                }
            }
        }
    }
}

@Composable
private fun PlayerOverlay(modifier: Modifier, fireworks: Boolean = false, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = .9f)).pointerInput(Unit) { detectTapGestures { } }) {
        if (fireworks) FireworksCanvas(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) { content() }
    }
}

@Composable
private fun PlayerInfo(ctl: AppController, entry: ViewEntry.Player, modifier: Modifier) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val e = entry.engine
    val a = e.anime
    val ep = e.episode
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = Gutter, end = Gutter, top = 24.dp, bottom = 16.dp), 
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        val ctx = LocalContext.current
        val dlManager = remember(ctx) { MkvDownloadManager(ctx.applicationContext) }
        val targetQuality = remember(ctl.persisted.settings.quality) {
            MkvQuality.fromText(ctl.persisted.settings.quality) ?: MkvQuality.P1080
        }
        val animeSlug = remember(a.romaji, ep) {
            StreamResolver.buildEpisodeSlug(a.romaji.ifBlank { a.title }, ep).substringBefore("-ep-")
        }
        val workName = remember(animeSlug, ep, targetQuality) {
            "mkv_dl_${animeSlug}_ep_${ep}_${targetQuality.label}"
        }
        val dlStatus by dlManager.observeDownload(workName).collectAsState(initial = MkvDownloadStatus())
        val alreadyDownloaded = remember(animeSlug, ep, targetQuality, dlStatus.isSucceeded) {
            dlManager.getDownloadedFile(animeSlug, ep, targetQuality).let { it.exists() && it.length() > 1024L }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MilkText(ctl.episodeLineOf(a, ep), ty.title, c.onSurface)
            MilkText("${a.romaji} ${tr.t("player.minutesSuffix", "episodeMinutes" to a.episodeMinutes)}", ty.body, c.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val btnLabel = when {
                    alreadyDownloaded || dlStatus.isSucceeded -> "MKV ${targetQuality.label} Tersimpan"
                    dlStatus.isRunning -> "Mengunduh ${dlStatus.progressPercent}%..."
                    dlStatus.isFailed -> "Gagal • Unduh Ulang MKV"
                    else -> "Unduh MKV ${targetQuality.label}"
                }
                MilkButton(
                    text = btnLabel,
                    onClick = {
                        if (!dlStatus.isRunning && !alreadyDownloaded) {
                            runCatching {
                                dlManager.enqueueEpisodeDownload(
                                    MkvDownloadRequest(
                                        animeSlug = animeSlug,
                                        animeTitle = a.romaji.ifBlank { a.title },
                                        episodeNumber = ep,
                                        preferredQuality = targetQuality
                                    )
                                )
                            }.onFailure { err ->
                                ctl.showToast(err.message ?: "Gagal memulai unduhan MKV")
                            }
                        }
                    },
                    variant = if (alreadyDownloaded || dlStatus.isSucceeded) ButtonVariant.Outlined else ButtonVariant.Primary,
                    small = true,
                    dimmed = dlStatus.isRunning || alreadyDownloaded
                )
                if (dlStatus.isRunning) {
                    MilkButton(
                        text = "Batal",
                        onClick = { runCatching { dlManager.cancelDownload(workName) } },
                        variant = ButtonVariant.Outlined,
                        small = true
                    )
                }
            }
        }
        
        val mx = minOf(a.totalEpisodes, time.releasedEpisodeCount(a) + if (time.isOngoing(a)) 1 else 0)
        if (mx >= 2) {
            Column {
                SectionHeader(ctl.enumLabel("detailTab", "episodes"))
                EpisodeStrip(ctl, a, (1..mx).toList(), ep, androidx.compose.runtime.remember(a.id, c.surface) { com.milkglass.app.core.design.accentFor(a, c.surface) })
            }
        }
        LikeSection(ctl, entry.rec.mapNotNull { ctl.animeOf(it) }.take(9)) { SectionHeader(tr.t("home.youMightLike")) }
    }
}

private const val DOUBLE_TAP_MS = 280L
private const val SEEK_MODE_MS = 700L

private const val SEEK_FX_MS = 700L

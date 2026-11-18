package com.milkglass.app.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.NavKind
import com.milkglass.app.core.app.TAB_PAGES
import com.milkglass.app.core.app.ViewEntry
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkEasing
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.MilkMotion
import com.milkglass.app.ui.common.AvatarButton
import com.milkglass.app.ui.common.MilkIconButton
import com.milkglass.app.ui.common.TopBar
import com.milkglass.app.ui.screens.CollectionPage
import com.milkglass.app.ui.screens.PlayerScreen
import com.milkglass.app.ui.screens.SocialPage
import com.milkglass.app.ui.screens.DetailScreen
import com.milkglass.app.ui.screens.HomeScreen
import com.milkglass.app.ui.screens.LoginScreen
import com.milkglass.app.ui.screens.ProfilePage
import com.milkglass.app.ui.screens.FireworksCanvas
import com.milkglass.app.ui.screens.ScheduleScreen
import com.milkglass.app.ui.screens.SearchScreen
import com.milkglass.app.ui.screens.SplashScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppRoot(ctl: AppController) {
    val c = Milk.colors
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    
    LaunchedEffect(Unit) {
        ctl.boot { urls ->
            
            kotlinx.coroutines.coroutineScope {
                launch(kotlinx.coroutines.Dispatchers.Default) { ctl.warmCaches() }
                com.milkglass.app.core.poster.prefetchPosters(appCtx, urls)
            }
        }
    }
    
    val catalogSize = ctl.catalog.size
    LaunchedEffect(ctl.isBooting, catalogSize) {
        if (!ctl.isBooting && ctl.online) {
            delay(1_500) 
            if (!com.milkglass.app.core.poster.isMeteredNetwork(appCtx)) {
                com.milkglass.app.core.poster.prefetchPosters(appCtx, ctl.secondaryPosterUrls(), parallelism = 3)
            }
        }
    }
    
    LaunchedEffect(ctl.online) { if (ctl.online) ctl.retryCatalogIfFailed() }
    val owner = LocalLifecycleOwner.current
    
    
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ctl.tick = System.currentTimeMillis() 
            
            launch { ctl.loadRemoteCatalog() }
            launch { while (true) { delay(20_000); ctl.tick = System.currentTimeMillis() } }
            while (true) { delay(4_000); ctl.saveInBackground() }
        }
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) ctl.saveNow() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    
    val fullscreenPlayer = (ctl.viewStack.lastOrNull() as? ViewEntry.Player)?.engine?.isFullscreen == true
    Box(Modifier.fillMaxSize().background(c.surfaceVariant), contentAlignment = Alignment.TopCenter) {
        Box(
            if (fullscreenPlayer) {
                Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
            } else {
                Modifier.widthIn(max = 460.dp).fillMaxHeight()
                    .drawBehind {
                        val r = 1.dp.toPx()
                        drawRect(c.outlineVariant, Offset(-r, -r), Size(size.width + 2 * r, size.height + 2 * r))
                    }
                    .background(c.surface)
            },
        ) {
            when {
                ctl.isBooting -> SplashScreen()
                ctl.persisted.isAuthenticated == 0 -> LoginScreen(ctl)
                else -> MainFlow(ctl)
            }
            OverlayHost(ctl)
            GlobalFireworks(ctl)
            ToastHost(ctl)
            
            BackHandler(enabled = ctl.overlay != null) { ctl.closeOverlay() }
        }
    }
}

private enum class FxKind { Enter, Back }
private data class PaneFx(val page: Int, val kind: FxKind, val token: Int)

@Composable
private fun MainFlow(ctl: AppController) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 1) { TAB_PAGES.size }
    val scrolls = rememberScrollStates(TAB_PAGES.size)
    val collScrolls = rememberScrollStates(4)
    val activeTab = TAB_PAGES[pagerState.targetPage]
    
    
    
    val activeTabNow: () -> String = remember(pagerState) { { TAB_PAGES[pagerState.targetPage] } }
    var fx by remember { mutableStateOf<PaneFx?>(null) }
    var fxCounter by remember { mutableStateOf(0) }
    val tr = ctl.tr

    
    
    
    
    val holder = rememberSaveableStateHolder()
    val topEntry = ctl.viewStack.lastOrNull()
    var settledFor by remember { mutableStateOf<ViewEntry?>(null) }
    LaunchedEffect(topEntry) {
        if (topEntry is ViewEntry.Player) { delay(320); settledFor = topEntry } else settledFor = null
    }
    
    val covered = topEntry is ViewEntry.Player && settledFor is ViewEntry.Player

    LaunchedEffect(activeTab) { if (activeTab != "profile") ctl.tabBeforeProfile = activeTab }
    
    LaunchedEffect(activeTab, ctl.viewStack.size, ctl.persisted.collectionTab) { ctl.validateSelection(activeTab) }

    val selectTab: (String) -> Unit = remember(pagerState, scope, scrolls) {
        { t ->
            val i = TAB_PAGES.indexOf(t)
            if (t == TAB_PAGES[pagerState.targetPage]) scope.launch { scrolls[i].animateScrollTo(0) }
            else scope.launch { pagerState.animateScrollToPage(i, animationSpec = tween(280, easing = MilkEasing.Decelerate)) }
        }
    }
    fun openProfile() {
        if (activeTab != "profile") scope.launch { pagerState.scrollToPage(0); fx = PaneFx(0, FxKind.Enter, ++fxCounter) }
    }
    fun closeProfile() {
        val back = ctl.tabBeforeProfile.takeIf { it != "profile" } ?: "home"
        val i = TAB_PAGES.indexOf(back)
        scope.launch { pagerState.scrollToPage(i); fx = PaneFx(i, FxKind.Back, ++fxCounter) }
    }

    
    
    
    val pop = remember { Animatable(1f) }
    LaunchedEffect(ctl.navToken) {
        if (ctl.navKind == NavKind.Pop && ctl.popFromPlayer) { if (pop.value > .98f) pop.snapTo(0f); pop.animateTo(1f, tween(220, easing = MilkEasing.EaseOut)) }
    }

    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val p = pop.value
            alpha = p
            val s = 1.03f - .03f * p
            scaleX = s; scaleY = s
        },
    ) {
        if (!covered) holder.SaveableStateProvider("main") {
        Column(Modifier.fillMaxSize()) {
            if (activeTab == "profile") {
                TopBar(tr.t("common.profile"), bare = true, leading = { MilkIconButton(MilkIcons.Back, { closeProfile() }) })
            } else {
                val title = when (activeTab) {
                    "home" -> "Milkglass"
                    "schedule" -> tr.t("schedule.title")
                    else -> ctl.enumLabel("mainTab", activeTab)
                }
                TopBar(title, bare = false) {
                    MilkIconButton(MilkIcons.Search, { ctl.openSearch() })
                    AvatarButton(ctl.meName.take(1).uppercase()) { openProfile() }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top, 
                beyondViewportPageCount = TAB_PAGES.size, 
                flingBehavior = PagerDefaults.flingBehavior(pagerState, snapPositionalThreshold = .28f),
            ) { page ->
                PaneFxBox(page, fx) {
                    when (TAB_PAGES[page]) {
                        "profile" -> ProfilePage(ctl, scrolls[page])
                        "home" -> HomeScreen(ctl, scrolls[page], onSelectTab = selectTab)
                        "schedule" -> ScheduleScreen(ctl, scrolls[page])
                        "social" -> SocialPage(ctl, scrolls[page])
                        else -> CollectionPage(ctl, collScrolls, activeTabNow)
                    }
                }
            }
            NavBar(ctl, activeTab, selectTab)
        }
        }

        
        
        val shown = ctl.viewStack.mapIndexed { i, e -> Triple(i, e, 0) } + ctl.exitingViews.map { Triple(it.index, it.entry, it.id) }
        shown.sortedBy { it.first }.forEach { (idx, entry, exitId) ->
            key(idx, entry) {
                
                if (!(covered && idx != ctl.viewStack.lastIndex)) {
                    
                    
                    
                    val stateKey = "$idx:${System.identityHashCode(entry)}"
                    holder.SaveableStateProvider(stateKey) {
                        
                        DisposableEffect(stateKey) {
                            onDispose { if (ctl.viewStack.getOrNull(idx) !== entry) holder.removeState(stateKey) }
                        }
                        PushedView(
                            isNew = exitId == 0 && idx == ctl.viewStack.lastIndex && ctl.navKind == NavKind.Push,
                            exiting = exitId != 0,
                            onExited = { ctl.finishExit(exitId) },
                        ) {
                            when (entry) {
                                ViewEntry.Search -> SearchScreen(ctl)
                                is ViewEntry.Detail -> DetailScreen(ctl, entry.id)
                                is ViewEntry.Player -> PlayerScreen(ctl, entry)
                            }
                        }
                    }
                }
            }
        }
        BackHandler(enabled = ctl.viewStack.isNotEmpty() || activeTab == "profile") {
            if (ctl.viewStack.isNotEmpty()) ctl.goBack() else closeProfile()
        }
    }
}

@Composable
private fun PushedView(isNew: Boolean, exiting: Boolean, onExited: () -> Unit, content: @Composable () -> Unit) {
    val p = remember { Animatable(if (isNew) 0f else 1f) }
    val kb = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { if (isNew) p.animateTo(1f, tween(240, easing = MilkEasing.Decelerate)) }
    LaunchedEffect(exiting) {
        if (exiting) {
            
            focus.clearFocus(); kb?.hide()
            p.animateTo(0f, tween(MilkMotion.viewExit.durationMs, easing = MilkMotion.viewExit.easing))
            onExited()
        }
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = p.value; translationX = 28.dp.toPx() * (1f - p.value) }
            .background(Milk.colors.surface)
            
            
            .pointerInput(exiting) {
                if (exiting) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
    ) { content() }
}

@Composable
private fun PaneFxBox(page: Int, fx: PaneFx?, content: @Composable () -> Unit) {
    val p = remember { Animatable(1f) }
    val active = fx?.takeIf { it.page == page }
    LaunchedEffect(active?.token) {
        if (active != null) {
            p.snapTo(0f)
            p.animateTo(1f, tween(if (active.kind == FxKind.Enter) 300 else 260, easing = MilkEasing.Decelerate))
        }
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val kind = active?.kind
            alpha = if (kind == FxKind.Back) .4f + .6f * p.value else p.value
            val from = if (kind == FxKind.Enter) .82f else if (kind == FxKind.Back) 1.03f else 1f
            val s = from + (1f - from) * p.value
            scaleX = s; scaleY = s
            transformOrigin = if (kind == FxKind.Enter) TransformOrigin(.92f, 0f) else TransformOrigin.Center
        },
    ) { content() }
}

@Composable
private fun GlobalFireworks(ctl: AppController) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(ctl.fireworksToken) {
        if (ctl.fireworksToken > 0) { show = true; delay(3400); show = false }
    }
    if (show) FireworksCanvas(Modifier.fillMaxSize(), iterations = 2)
}

@Composable
private fun rememberScrollStates(n: Int): List<androidx.compose.foundation.ScrollState> {
    val l = List(n) { rememberScrollState() }
    return remember(*l.toTypedArray()) { l }
}

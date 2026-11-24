package com.milkglass.app.ui.screens

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.luminance
import com.milkglass.app.core.design.accentFor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import id.my.milkglass.EngineRuntime
import id.my.milkglass.player.PlayerManager
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first
import com.milkglass.app.ui.common.ArtBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.Access
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.cssLinearGradient
import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.core.model.Anime
import com.milkglass.app.ui.common.BadgeKind
import com.milkglass.app.ui.common.ButtonVariant
import com.milkglass.app.ui.common.Clamp1
import com.milkglass.app.ui.common.Gutter
import com.milkglass.app.ui.common.LabelStrong
import com.milkglass.app.ui.common.MilkBadge
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkChip
import com.milkglass.app.ui.common.MilkDivider
import com.milkglass.app.ui.common.MilkIconButton
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.PosterView
import com.milkglass.app.ui.common.ProgressBar
import com.milkglass.app.ui.common.TopBar
import com.milkglass.app.ui.common.bleed
import com.milkglass.app.ui.common.featureOffLook
import com.milkglass.app.ui.common.lockedThumbLook
import com.milkglass.app.ui.common.milkClickable
import com.milkglass.app.ui.common.dimmedLook
import kotlin.math.abs
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
private val DETAIL_TABS = listOf("episodes", "similar")

@Composable
fun DetailScreen(ctl: AppController, id: Int) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val a = ctl.animeOf(id) ?: run { LaunchedEffect(id) { ctl.goBack() }; return }
    val resume = ctl.continueInfo(a)
    val released = ctl.effectiveReleasedCount(a)
    val totalEps = ctl.effectiveTotalCount(a)
    val mx = maxOf(released, minOf(totalEps, released + if (time.isOngoing(a)) 1 else 0))
    val hasEp0 = ctl.hasEpisodeZero(a)
    val startEp = if (hasEp0) 0 else 1
    val eps = remember(startEp, mx, ctl.episodesDescending) { (startEp..mx).toList().let { if (ctl.episodesDescending) it.reversed() else it } }
    val n = time.nextReleaseAt(a)
    var detailTab by rememberSaveable(id) { mutableStateOf("episodes") }
    var synopsisExpanded by rememberSaveable(id) { mutableStateOf(false) }

    val statusText = if (time.isOngoing(a) && n != null) tr.t("detail.airing", "day" to tr.locale.weekdaysLong[time.weekdayOf(n)])
        else tr.t("detail.finished", "totalEpisodes" to totalEps)
    val statusKind = if (time.isOngoing(a) && n != null) BadgeKind.Success else BadgeKind.Neutral
    val accent = remember(a.id, c.surface) { accentFor(a, c.surface) }
    LaunchedEffect(a.id) { ctl.loadEpisodeMeta(a) }
    LaunchedEffect(a.id) { ctl.loadExtras(a) }
    val detailCtx = LocalContext.current.applicationContext
    LaunchedEffect(a.id, ctl.online) {
        if (ctl.online && (a.sourceUrls.isNotEmpty() || a.romaji.isNotBlank())) {
            val rt = EngineRuntime.get(detailCtx)
            rt.prewarmCoreHostConnections()
            rt.seedSourceUrls(a.title, a.romaji, a.sourceUrls)
            val live = rt.probeLiveEpisodes(a.title, a.romaji, a.sourceUrls)
            if (live != null && (live.maxEpisode > 0 || live.hasEpisodeZero)) {
                ctl.reportLiveEpisodes(a.id, live.maxEpisode, live.hasEpisodeZero)
            }
        }
    }
    LaunchedEffect(a.id, resume?.episode, resume?.waiting, ctl.preferredQuality, ctl.online) {
        val targetEp = when {
            resume == null -> 1
            resume.waiting && resume.completed -> 1
            resume.waiting -> null
            else -> resume.episode ?: 1
        }
        if (targetEp != null && ctl.online && ctl.episodeAccess(a, targetEp) == Access.Ok) {
            val rt = EngineRuntime.get(detailCtx)
            rt.prewarmCoreHostConnections()
            rt.seedSourceUrls(a.title, a.romaji, a.sourceUrls)
            val surroundEps = listOf(targetEp, targetEp + 1, targetEp + 2, targetEp + 3, targetEp - 1)
                .filter { it in 1..mx && ctl.episodeAccess(a, it) == Access.Ok }
                .distinct()
                .map { epNum -> id.my.milkglass.resolver.StreamResolver.BatchPrefetchItem(a.id, a.title, a.romaji, epNum) }
            if (surroundEps.isNotEmpty()) {
                rt.prefetchEpisodeBatch(surroundEps, ctl.preferredQuality)
            }
            val metered = com.milkglass.app.core.poster.isMeteredNetwork(detailCtx)
            rt.prefetchEpisode(
                a.title, a.romaji, targetEp, ctl.preferredQuality,
                prebuffer = true,
                prebufferHeadBytes = if (metered) {
                    PlayerManager.PREBUFFER_METERED_HEAD_BYTES
                } else {
                    PlayerManager.PREBUFFER_HEAD_BYTES
                },
                anilistId = a.id,
            )
            val nextEp = targetEp + 1
            if (nextEp <= mx && ctl.episodeAccess(a, nextEp) == Access.Ok) {
                rt.prefetchEpisode(
                    a.title, a.romaji, nextEp, ctl.preferredQuality,
                    prebuffer = false,
                    anilistId = a.id,
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        TopBar("", bare = true, leading = { MilkIconButton(MilkIcons.Back, { ctl.goBack() }) }) {
            WatchlistToggleButton(ctl, a.id)
        }
        val scroll = rememberScrollState()

        val ctaPin = remember(id) { StickyPin() }
        val tabsPin = remember(id) { StickyPin() }
        var viewportTop by remember(id) { mutableFloatStateOf(0f) }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current

            val expandedPx = with(density) { minOf((maxWidth - Gutter * 2) * 1.25f, maxHeight * 0.44f).toPx() }
            val compactPx = with(density) { 168.dp.toPx() }
            var headerPx by remember(expandedPx) { mutableFloatStateOf(expandedPx) }
            val conn = remember(expandedPx, compactPx) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        val dy = available.y
                        if (dy >= 0f || headerPx <= compactPx) return Offset.Zero
                        val next = (headerPx + dy).coerceAtLeast(compactPx)
                        val used = next - headerPx
                        headerPx = next
                        return Offset(0f, used)
                    }
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        val dy = available.y
                        if (dy <= 0f || headerPx >= expandedPx) return Offset.Zero
                        val next = (headerPx + dy).coerceAtMost(expandedPx)
                        val used = next - headerPx
                        headerPx = next
                        return Offset(0f, used)
                    }
                }
            }
            Column(Modifier.fillMaxSize().nestedScroll(conn)) {
                DetailHero(ctl, a, { headerPx }, expandedPx, compactPx, statusText, statusKind, Modifier.padding(start = Gutter, end = Gutter, top = 8.dp))
                PageColumn(scroll, Modifier.weight(1f).padding(top = 8.dp).onGloballyPositioned { viewportTop = it.positionInRoot().y }) {

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MilkText(
                    listOfNotNull(a.studio.takeIf { it.isNotBlank() }, a.year.takeIf { it > 0 }?.toString(), tr.t("detail.fans", "count" to time.formatCompactCount(a.voteCount))).joinToString(" · "),
                    ty.caption, c.onSurfaceVariant, Modifier.fillMaxWidth(), maxLines = 1, ellipsize = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.genres.forEach { g -> key(g) { MilkBadge(ctl.enumLabel("genre", g), onClick = { ctl.searchByGenre(g) }) } }
                    }
                    MilkBadge(
                        statusText, statusKind,
                        Modifier.graphicsLayer { alpha = ((headerPx - compactPx) / (expandedPx - compactPx)).coerceIn(0f, 1f) },
                    )
                }
            }

            Box(
                Modifier.fillMaxWidth().bleed(Gutter)
                    .sticky(scroll, { viewportTop }, ctaPin)
                    .background(c.surface)
                    .padding(horizontal = Gutter, vertical = 8.dp),
            ) {
            if (resume != null) {
                if (resume.waiting) {
                    if (resume.completed) MilkButton(tr.t("detail.rewatchEp1"), { ctl.playEpisode(a.id, 1) }, wide = true, variant = ButtonVariant.Primary)
                    else MilkButton(tr.t("detail.waitingNewEpisode"), { }, wide = true, dimmed = true)
                } else {
                    MilkButton(ctl.continueLabel(a), { ctl.playEpisode(a.id) }, icon = MilkIcons.Play, wide = true, variant = ButtonVariant.Primary)
                }
            } else {
                MilkButton(tr.t("detail.startEp1"), { ctl.playEpisode(a.id, 1) }, icon = MilkIcons.Play, wide = true, variant = ButtonVariant.Primary)
            }
            }

            Column {
                MilkText(
                    a.synopsis, ty.style(14f), c.onSurface, Modifier.fillMaxWidth(),
                    maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3, ellipsize = true,
                )
                if (a.synopsis.length > 120) {
                    LabelStrong(if (synopsisExpanded) tr.t("detail.collapse") else tr.t("detail.readMore"), { synopsisExpanded = !synopsisExpanded })
                }
            }

            Column(
                Modifier.fillMaxWidth().bleed(Gutter)
                    .sticky(scroll, { viewportTop }, tabsPin, above = { ctaPin.height })
                    .background(c.surface),
            ) {
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    DETAIL_TABS.forEach { t -> key(t) {
                        val active = t == detailTab
                        Box(
                            Modifier.weight(1f).height(48.dp)
                                .milkClickable(pressDim = false) { detailTab = t },
                            contentAlignment = Alignment.Center,
                        ) {
                            MilkText(ctl.enumLabel("detailTab", t), ty.tab, if (active) accent else c.onSurfaceVariant)
                            if (active) Box(
                                Modifier.align(Alignment.BottomCenter).width(56.dp).height(4.dp)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(accent),
                            )
                        }
                    } }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
            }

            when (detailTab) {
                "episodes" -> Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        MilkText(
                            (if (released >= totalEps) tr.t("detail.episodesAll", "totalEpisodes" to totalEps)
                            else tr.t("detail.episodesAvailable", "released" to released, "totalEpisodes" to totalEps)) +
                                " · " + tr.t("episode.minutes", "episodeMinutes" to a.episodeMinutes),
                            ty.caption, c.onSurfaceVariant, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        SortChip(ctl)
                    }
                    MilkCard(Modifier.padding(top = 8.dp)) {
                        eps.forEachIndexed { i, e ->
                            key(e) {
                                if (i > 0) MilkDivider()
                                EpisodeRow(ctl, a, e)
                            }
                        }
                    }
                }
                "comments" -> Unit
                else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val extrasForAnime = ctl.extrasOf(a.id)
                    val similarRows = remember(a.id, ctl.catalog, extrasForAnime) { ctl.similarOf(a).chunked(2) }
                    similarRows.forEach { row ->
                        key(row.first().id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { b ->
                                key(b.id) {
                                    Column(Modifier.weight(1f).milkClickable { ctl.openDetail(b.id) }) {
                                        PosterView(ctl.posterSpec(b, false), b, time.formatRating(b.rating), Modifier.fillMaxWidth())
                                        Clamp1(b.romaji, ty.subtitle, c.onSurface, Modifier.padding(top = 8.dp).fillMaxWidth())
                                        Clamp1(ctl.genreSummary(b), ty.caption, c.onSurfaceVariant, Modifier.fillMaxWidth())
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        }
                    }
                }
            }
        }
                }
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailHero(
    ctl: AppController, a: Anime, heightPx: () -> Float, expandedPx: Float, compactPx: Float,
    statusText: String, statusKind: BadgeKind, modifier: Modifier = Modifier,
) {
    val ty = Milk.type
    val time = ctl.time
    val frac = { ((heightPx() - compactPx) / (expandedPx - compactPx)).coerceIn(0f, 1f) }
    Box(
        modifier.fillMaxWidth()
            .layout { m, cs ->
                val h = heightPx().toInt()
                val p = m.measure(cs.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            }
            .clip(RoundedCornerShape(28.dp)),
    ) {

        ArtBackdrop(ctl.posterSpec(a, true), a, shadeAlpha = .5f, shadeStop = .8f, modifier = Modifier.fillMaxSize()) {}
        ArtBackdrop(
            ctl.posterSpec(a, false), a, shadeAlpha = .65f, shadeStop = .7f,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = frac() },
        ) {}

        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(16.dp).graphicsLayer { alpha = 1f - frac() },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {

            PosterView(ctl.posterSpec(a, false), a, time.formatRating(a.rating), Modifier.width(96.dp), small = true)
            Column(Modifier.weight(1f)) {
                MilkText(a.romaji, ty.title, Color.White)
                MilkText(a.title, ty.style(12f, 400), Color.White.copy(alpha = .85f), Modifier.padding(top = 2.dp))

                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    MilkBadge("★ ${time.formatRating(a.rating)}", BadgeKind.RatingOnArt)
                    if (a.ageRating.isNotEmpty()) MilkBadge(a.ageRating, BadgeKind.NeutralOnArt)
                    MilkBadge(statusText, if (statusKind == BadgeKind.Neutral) BadgeKind.NeutralOnArt else statusKind)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(16.dp).graphicsLayer { alpha = frac() },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MilkText(a.romaji, ty.style(28f, 700, 36f), Color.White)
            MilkText(a.title, ty.style(14f, 400), Color.White.copy(alpha = .85f))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MilkBadge("★ ${time.formatRating(a.rating)}", BadgeKind.RatingOnArt)
                if (a.ageRating.isNotEmpty()) MilkBadge(a.ageRating, BadgeKind.NeutralOnArt)
            }
        }
    }
}

@Composable
internal fun EpisodeStrip(ctl: AppController, a: Anime, eps: List<Int>, current: Int?, accent: Color) {
    val c = Milk.colors
    val ty = Milk.type
    val idx = eps.indexOf(current ?: -1)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 2).coerceAtLeast(0))
    val itemPx = with(LocalDensity.current) { 40.dp.toPx() }
    val firstPlacement = remember { androidx.compose.runtime.mutableStateOf(true) }

    LaunchedEffect(current, eps.size) {
        if (idx < 0) return@LaunchedEffect
        val viewportW = androidx.compose.runtime.snapshotFlow { state.layoutInfo.viewportSize.width }.first { it > 0 }
        val offset = -((viewportW - itemPx) / 2f).toInt()
        if (firstPlacement.value) { state.scrollToItem(idx, offset); firstPlacement.value = false }
        else state.animateScrollToItem(idx, offset)
    }
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 8.dp).bleed(Gutter),
        state = state,
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(eps, key = { it }) { e ->
            val isCurrent = e == current
            val watched = ctl.isWatched(a, e)
            val playable = ctl.episodeAccess(a, e) == Access.Ok
            val bg = if (isCurrent) accent else if (watched) c.primaryContainer else c.scHighest
            val fg = if (isCurrent) (if (accent.luminance() > 0.5f) Color.Black else Color.White) else if (watched) c.onPrimaryContainer else c.onSurfaceVariant
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(bg)
                    .then(if (playable) Modifier else Modifier.graphicsLayer { alpha = .38f })
                    .milkClickable { if (!isCurrent) ctl.openEpisode(a, e) },
                contentAlignment = Alignment.Center,
            ) { MilkText("$e", ty.style(14f, 500), fg) }
        }
    }
}

@Composable
private fun SortChip(ctl: AppController) {
    MilkChip(
        if (ctl.episodesDescending) ctl.tr.t("detail.oldestFirst") else ctl.tr.t("detail.newestFirst"),
        active = false, onClick = { ctl.episodesDescending = !ctl.episodesDescending },
    )
}

@Composable
private fun EpisodeRow(ctl: AppController, a: Anime, ep: Int) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val st = ctl.episodeAccess(a, ep)
    val p = ctl.progressOf(a, ep)
    val w = ctl.isWatched(a, ep)
    val at = ctl.episodeAirTime(a, ep)

    val sub: String? = when {
        w -> tr.t("episode.watched")
        p != 0.0 -> tr.t("episode.remaining", "max" to max(1, Math.round((ctl.durationSeconds(a) - p) / 60).toInt()))
        st == Access.Ok -> null
        st == Access.Early -> tr.t("episode.premiumEarly")
        at != null -> tr.t("episode.airsAt", "WEEKDAYS_LONG" to tr.locale.weekdaysLong[time.weekdayOf(at)], "formatClock" to time.formatClock(at))
        else -> tr.t("episode.notAired")
    }

    val isPrem = ctl.isPremium()
    val noIcon = st == Access.Ok && !(w && !isPrem)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 12.dp, end = if (noIcon) 16.dp else 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).milkClickable { ctl.openEpisode(a, ep) },
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbBg = remember(a.colorFrom, a.colorTo, ep) {
                val from = parseCssColor(a.colorFrom) ?: Color.Black
                val to = parseCssColor(a.colorTo) ?: Color.Black
                cssLinearGradient((120 + ep * 17).toFloat(), listOf(from, to))
            }
            val thumb = ctl.episodeThumbOf(a, ep)
            Box(
                Modifier.size(72.dp, 40.dp).clip(RoundedCornerShape(8.dp))
                    .let { if (st == Access.Lock) it.lockedThumbLook() else it }
                    .background(thumbBg),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    coil.compose.AsyncImage(
                        model = thumb, contentDescription = null, modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)))
                    MilkText("$ep", ty.episodeThumb, Color.White, Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp))
                } else MilkText("$ep", ty.episodeThumb, Color.White)
                if (p != 0.0) ProgressBar((p / ctl.durationSeconds(a) * 100).toFloat(), 4.dp, Color.Black.copy(alpha = .4f), Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f)) {

                Clamp1(ctl.episodeTitleOf(a, ep), ty.style(16f, 500, 22f, .1f), c.onSurface, Modifier.fillMaxWidth())
                if (sub != null) MilkText(sub, ty.caption, c.onSurfaceVariant)
            }
            if (!noIcon) EpisodeStateIcon(st = st, watched = w, isPremium = isPrem)
        }
    }
}

@Composable
private fun WatchlistToggleButton(ctl: AppController, animeId: Int) {
    val inList by remember(ctl, animeId) { derivedStateOf { ctl.isInWatchlist(animeId) } }
    MilkIconButton(if (inList) MilkIcons.Bm else MilkIcons.BmO, { ctl.toggleWatchlist(animeId) })
}

@Composable
private fun EpisodeStateIcon(st: Access, watched: Boolean, isPremium: Boolean) {
    val c = Milk.colors
    val m = Modifier.size(40.dp).clip(CircleShape)
    when {
        st == Access.Ok && watched && !isPremium ->
            Box(m, contentAlignment = Alignment.Center) { MilkIcon(MilkIcons.Check, size = 24.dp, tint = c.success) }
        st == Access.Ok -> Unit
        else ->
            Box(m, contentAlignment = Alignment.Center) {
                MilkIcon(if (st == Access.Early) MilkIcons.Crown else MilkIcons.Lock, size = 24.dp, tint = c.premiumText)
            }
    }
}

private class StickyPin {
    var top by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
}

private fun Modifier.sticky(scroll: ScrollState, viewportTop: () -> Float, pin: StickyPin, above: () -> Float = { 0f }): Modifier = this
    .onGloballyPositioned {
        val top = it.positionInRoot().y - viewportTop() + scroll.value
        if (abs(top - pin.top) > .5f) pin.top = top
        pin.height = it.size.height.toFloat()
    }
    .zIndex(1f)
    .graphicsLayer { translationY = max(0f, scroll.value + above() - pin.top) }

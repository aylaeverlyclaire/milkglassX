package com.milkglass.app.ui.screens

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import com.milkglass.app.core.design.accentFor
import com.milkglass.app.ui.common.milkCombinedClickable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.model.Anime
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.ui.common.ArtBackdrop
import com.milkglass.app.ui.common.BadgeKind
import com.milkglass.app.ui.common.ButtonVariant
import com.milkglass.app.ui.common.ChevronIcon
import com.milkglass.app.ui.common.Clamp1
import com.milkglass.app.ui.common.Gutter
import com.milkglass.app.ui.common.LabelStrong
import com.milkglass.app.ui.common.MilkBadge
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkDivider
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.PosterView
import com.milkglass.app.ui.common.SectionHeader
import com.milkglass.app.ui.common.bleed
import com.milkglass.app.ui.common.featureOffLook
import com.milkglass.app.ui.common.milkClickable

@Composable
fun PageColumn(scroll: ScrollState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().verticalScroll(scroll)
            .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
fun Poster(ctl: AppController, a: Anime, width: androidx.compose.ui.unit.Dp, progressPct: Float? = null, modifier: Modifier = Modifier) {
    val small = width.value < 80f
    PosterView(
        ctl.posterSpec(a, false), a, ctl.time.formatRating(a.rating),
        modifier.width(width), small = small, progressPct = progressPct,
    )
}

@Composable
fun <T> Carousel(
    items: List<T>,
    gap: androidx.compose.ui.unit.Dp = 12.dp,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = { (it as? Anime)?.id ?: it.hashCode() },
    item: @Composable (T) -> Unit,
) {
    LazyRow(
        modifier.fillMaxWidth().bleed(Gutter),
        contentPadding = PaddingValues(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) { items(items, key = key) { item(it) } }
}

@Composable
fun AnimeCard(ctl: AppController, a: Anime, sub: String, progressPct: Float? = null, extra: @Composable () -> Unit = {}) {
    val c = Milk.colors
    val haptic = LocalHapticFeedback.current

    Column(
        Modifier.width(132.dp).milkCombinedClickable(
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctl.toggleWatchlist(a.id) },
            onClick = { ctl.openAndPlay(a.id) },
        ),
    ) {
        Poster(ctl, a, 132.dp, progressPct)
        Clamp1(a.romaji, Milk.type.subtitle, c.onSurface, Modifier.padding(top = 8.dp).fillMaxWidth())
        MilkText(sub, Milk.type.caption, c.onSurfaceVariant, Modifier.fillMaxWidth(), maxLines = 1, ellipsize = true)
        extra()
    }
}

@Composable
fun CountdownText(ctl: AppController, ts: Long, modifier: Modifier = Modifier, hideWhenAired: Boolean = false) {
    val now = ctl.nowMs
    val aired = ts <= now

    var prevAired by androidx.compose.runtime.remember(ts) { androidx.compose.runtime.mutableStateOf(aired) }
    androidx.compose.runtime.LaunchedEffect(aired) {
        if (aired && !prevAired) ctl.launchFireworks()
        prevAired = aired
    }
    if (aired && hideWhenAired) return

    val text = if (aired) ctl.tr.t("time.alreadyAired") else ctl.tr.t("schedule.inShort", "value" to ctl.time.formatCountdown(ts - ctl.now()))
    MilkText(text, Milk.type.countdown, if (aired) Milk.colors.success else Milk.colors.primary, modifier)
}

@Immutable
private data class HomeData(
    val cl: List<Anime>, val anime: Anime, val resume: com.milkglass.app.core.app.ContinueInfo?,
    val today: List<com.milkglass.app.core.app.ScheduleEntry>, val trending: List<Anime>, val fy: List<Anime>,
    val personalized: Boolean,
)

@Composable
private fun CatalogEmpty(ctl: AppController) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tr = ctl.tr
    com.milkglass.app.ui.common.EmptyState(topPadding = 160.dp) {
        if (ctl.catalogFailed && !ctl.catalogLoading) {
            MilkText(tr.t("home.catalogFailed"), Milk.type.subtitle, Milk.colors.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            MilkButton(tr.t("common.retry"), { scope.launch { ctl.loadRemoteCatalog(force = true) } })
        } else {
            MilkText(tr.t("home.catalogLoading"), Milk.type.subtitle, Milk.colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
fun HomeScreen(ctl: AppController, scroll: ScrollState, onSelectTab: (String) -> Unit) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    if (ctl.catalog.isEmpty()) { CatalogEmpty(ctl); return }
    val todayBucket = time.startOfToday()
    val progress = ctl.progressMap
    val resumePos = ctl.resumePositionsMap
    val lastWatched = ctl.lastWatchedMap
    val d = remember(progress, resumePos, lastWatched, ctl.catalog, todayBucket) {
        val cl = ctl.continueWatchingList()
        val anime = cl.firstOrNull() ?: ctl.trendingList().first()
        HomeData(
            cl, anime, if (cl.isNotEmpty()) ctl.continueInfo(anime) else null, ctl.scheduleForDay(0),
            ctl.trendingList().take(8), ctl.recommendedList().take(9), ctl.genreAffinity().isNotEmpty(),
        )
    }

    val heroCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(d.anime.id, d.resume?.episode, ctl.preferredQuality, ctl.online) {
        val ep = d.resume?.episode ?: 1
        if (ctl.online && ctl.episodeAccess(d.anime, ep) == com.milkglass.app.core.app.Access.Ok) {
            kotlinx.coroutines.delay(250)
            val rt = id.my.milkglass.EngineRuntime.get(heroCtx)
            rt.prewarmCoreHostConnections()
            rt.seedSourceUrls(d.anime.title, d.anime.romaji, d.anime.sourceUrls)
            val batchItems = buildList {
                d.cl.take(3).forEach { cw ->
                    val cwEp = ctl.continueInfo(cw).episode ?: 1
                    rt.seedSourceUrls(cw.title, cw.romaji, cw.sourceUrls)
                    add(id.my.milkglass.resolver.StreamResolver.BatchPrefetchItem(cw.id, cw.title, cw.romaji, cwEp))
                }
                d.trending.take(3).forEach { trAnime ->
                    if (none { it.animeId == trAnime.id }) {
                        rt.seedSourceUrls(trAnime.title, trAnime.romaji, trAnime.sourceUrls)
                        add(id.my.milkglass.resolver.StreamResolver.BatchPrefetchItem(trAnime.id, trAnime.title, trAnime.romaji, 1))
                    }
                }
            }
            if (batchItems.isNotEmpty()) {
                rt.prefetchEpisodeBatch(batchItems, ctl.preferredQuality)
            }
            rt.prefetchEpisode(
                d.anime.title, d.anime.romaji, ep, ctl.preferredQuality,
                anilistId = d.anime.id,
            )
        }
    }

    val stopped = remember(progress, lastWatched, d.cl, todayBucket) {
        d.cl.firstOrNull { ctl.isReturning(it) }?.let { s ->
            s to ctl.similarOf(s, 16).filter { ctl.startedEpisodes(it).isEmpty() }.take(8)
        }
    }
    val cl = d.cl
    val anime = d.anime
    val resume = d.resume
    val today = d.today
    val trending = d.trending
    val fy = d.fy

    PageColumn(scroll) {

        ArtBackdrop(
            ctl.posterSpec(anime, true), anime, shadeAlpha = .55f, shadeStop = .75f,
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).clip(RoundedCornerShape(28.dp)),
        ) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MilkBadge(
                    when {
                        resume != null && !resume.waiting && resume.episode != null && ctl.isReturning(anime) -> tr.t("home.becauseYouStopped", "episode" to resume.episode)
                        resume != null -> tr.t("home.continueWatching")
                        else -> tr.t("home.mostPopular")
                    },
                    BadgeKind.Success,
                )
                MilkText(anime.romaji, ty.heroTitle, Color.White)
                val left = resume?.takeIf { it.position != 0.0 }?.let {
                    tr.t("home.remainingMinutesSuffix", "max" to maxOf(1, Math.round((ctl.durationSeconds(anime) - it.position) / 60).toInt()))
                } ?: ""
                val ago = if (resume != null && ctl.lastWatchedAt(anime) > 0L) tr.t("home.agoSuffix", "ago" to time.formatTimeAgo(ctl.lastWatchedAt(anime))) else ""
                MilkText("${ctl.genreSummary(anime)} · ★ ${time.formatRating(anime.rating)}$left$ago", ty.heroMeta, Color.White.copy(alpha = .9f))
                if (resume != null && resume.position != 0.0) {
                    val pct = (resume.position / ctl.durationSeconds(anime)).toFloat().coerceIn(0f, 1f)
                    val accent = androidx.compose.runtime.remember(anime.id) { accentFor(anime, Color.Black) }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) {
                        Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(accent))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val primaryText: String
                    val primaryIcon: Int
                    val primaryAction: () -> Unit
                    if (resume != null && resume.waiting) {
                        primaryText = tr.t("home.viewSchedule"); primaryIcon = MilkIcons.Cal; primaryAction = { onSelectTab("schedule") }
                    } else if (resume != null) {
                        primaryText = if (resume.position != 0.0) tr.t("home.continueEpisode", "episode" to resume.episode) else tr.t("home.startEpisode", "episode" to resume.episode)
                        primaryIcon = MilkIcons.Play; primaryAction = { ctl.playEpisode(anime.id) }
                    } else {
                        primaryText = tr.t("home.watchEpisode1"); primaryIcon = MilkIcons.Play; primaryAction = { ctl.playEpisode(anime.id, 1) }
                    }
                    MilkButton(primaryText, primaryAction, Modifier.weight(1f), icon = primaryIcon, variant = ButtonVariant.Primary, onHero = true)
                    MilkButton(tr.t("home.detail"), { ctl.openDetail(anime.id) }, Modifier.weight(1f), icon = MilkIcons.Info, onHero = true)
                }
            }
        }

        if (cl.size > 1) {
            val moreWatching = remember(cl) { cl.drop(1) }
            Column {
                SectionHeader(tr.t("home.continueWatchingShort"))
                Carousel(moreWatching, modifier = Modifier.padding(top = 8.dp), key = { it.id }) { a ->
                    val r = ctl.continueInfo(a)
                    val pct = if (r != null && r.position != 0.0) (r.position / ctl.durationSeconds(a) * 100).toFloat() else 0f
                    AnimeCard(ctl, a, ctl.continueLabel(a), progressPct = pct)
                }
            }
        }

        if (stopped != null && stopped.second.isNotEmpty()) {
            Column {
                SectionHeader(tr.t("home.similarTo", "title" to stopped.first.romaji))
                Carousel(stopped.second, modifier = Modifier.padding(top = 8.dp), key = { it.id }) { a -> AnimeCard(ctl, a, ctl.genreSummary(a)) }
            }
        }

        if (today.isNotEmpty()) {
            Column {
                SectionHeader(tr.t("home.airingToday"), right = { LabelStrong(tr.t("home.allSchedule"), { onSelectTab("schedule") }) })
                Carousel(today, modifier = Modifier.padding(top = 8.dp), key = { "${it.a.id}.${it.ep}" }) { (a, ts, ep) ->
                    Column(Modifier.width(132.dp).milkClickable { ctl.openAndPlay(a.id) }) {
                        Poster(ctl, a, 132.dp)
                        Clamp1(a.romaji, ty.subtitle, c.onSurface, Modifier.padding(top = 8.dp).fillMaxWidth())
                        MilkText("${tr.t("home.episodePrefix", "ep" to ep)} · ${time.formatClock(ts)}", ty.caption, c.onSurfaceVariant)
                        CountdownText(ctl, ts)
                    }
                }
            }
        }

        Column {
            val rankedTrending = remember(trending) { trending.withIndex().toList() }
            SectionHeader(tr.t("home.mostPopular"))
            Carousel(rankedTrending, modifier = Modifier.padding(top = 8.dp), key = { it.value.id }) { (i, a) ->
                AnimeCard(ctl, a, tr.t("home.ratingCount", "value" to i + 1, "formatCompactCount" to time.formatCompactCount(a.voteCount)))
            }
        }

        if (fy.isNotEmpty()) {
            val personalized = d.personalized
            LikeSection(ctl, fy) {
                SectionHeader(if (personalized) tr.t("home.forYou") else tr.t("home.youMightLike"))
            }
        }
    }
}

@Composable
fun LikeSection(ctl: AppController, list: List<Anime>, head: @Composable () -> Unit) {
    val c = Milk.colors
    val ty = Milk.type
    val time = ctl.time
    val top = list.firstOrNull() ?: return
    val rest = list.drop(1)
    Column {
        head()
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailHeadCard(ctl, top, asButton = true)
            if (rest.isNotEmpty()) {
                MilkCard {
                    rest.forEachIndexed { i, b ->
                        key(b.id) {
                            if (i > 0) MilkDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 80.dp).milkClickable { ctl.openAndPlay(b.id) }
                                    .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Poster(ctl, b, 52.dp)
                                Column(Modifier.weight(1f)) {
                                    Clamp1(b.romaji, ty.subtitle, c.onSurface)
                                    Clamp1("${ctl.genreSummary(b)} · ★ ${time.formatRating(b.rating)}", ty.caption, c.onSurfaceVariant)
                                }
                                ChevronIcon()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailHeadCard(ctl: AppController, a: Anime, asButton: Boolean) {
    val ty = Milk.type
    val time = ctl.time
    ArtBackdrop(
        ctl.posterSpec(a, true), a, shadeAlpha = .5f, shadeStop = .8f,
        modifier = Modifier.fillMaxWidth().heightIn(min = 152.dp).clip(RoundedCornerShape(28.dp))
            .let { if (asButton) it.milkClickable { ctl.openAndPlay(a.id) } else it },
    ) {
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (!asButton) Poster(ctl, a, 96.dp)
            Column(Modifier.weight(1f)) {
                MilkText(a.romaji, ty.title, Color.White)
                MilkText(
                    if (asButton) ctl.genreSummary(a) else a.title,
                    ty.style(12f, 400), Color.White.copy(alpha = .85f), Modifier.padding(top = 2.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (asButton) {
                        MilkBadge("★ ${time.formatRating(a.rating)}", BadgeKind.RatingOnArt)
                        MilkBadge(a.year.toString(), BadgeKind.NeutralOnArt)
                    } else {
                        MilkBadge("★ ${time.formatRating(a.rating)}", BadgeKind.RatingOnArt)
                        if (a.ageRating.isNotEmpty()) MilkBadge(a.ageRating, BadgeKind.NeutralOnArt)
                        MilkBadge("${time.formatCompactCount(a.voteCount)} · ${a.year}", BadgeKind.NeutralOnArt)
                    }
                }
            }
        }
    }
}

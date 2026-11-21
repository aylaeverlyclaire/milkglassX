package com.milkglass.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkEasing
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.cssLinearGradient
import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.core.model.Anime
import com.milkglass.app.ui.common.BadgeKind
import com.milkglass.app.ui.common.Clamp1
import com.milkglass.app.ui.common.EmptyState
import com.milkglass.app.ui.common.MilkBadge
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkDivider
import com.milkglass.app.ui.common.MilkIconButton
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.SectionHeader
import com.milkglass.app.ui.common.milkClickable
import com.milkglass.app.ui.common.milkCombinedClickable
import kotlin.math.roundToInt

private val SUB_TABS = listOf("watchlist", "watching", "completed", "history")

@Composable
fun CollectionPage(ctl: AppController, scrolls: List<ScrollState>, activeTab: () -> String) {
    val c = Milk.colors
    val ty = Milk.type

    val index = SUB_TABS.indexOf(ctl.persisted.collectionTab).coerceAtLeast(0)
    val vc = LocalViewConfiguration.current
    val vc480 = remember(vc) { object : ViewConfiguration by vc { override val longPressTimeoutMillis: Long get() = 480L } }

    CompositionLocalProvider(LocalViewConfiguration provides vc480) {
        Column(Modifier.fillMaxSize()) {

            Box(Modifier.fillMaxWidth().height(48.dp)) {
                Row(Modifier.fillMaxSize()) {
                    SUB_TABS.forEachIndexed { i, name ->
                        key(name) {
                            Box(
                                Modifier.weight(1f).fillMaxHeight().milkClickable(pressDim = false) { ctl.selectCollectionTab(name) },
                                contentAlignment = Alignment.Center,
                            ) { MilkText(ctl.enumLabel("collectionTab", name), ty.tab, if (index == i) c.primary else c.onSurfaceVariant) }
                        }
                    }
                }
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val third = maxWidth / SUB_TABS.size.toFloat()
                    val frac by animateFloatAsState(index.toFloat(), tween(220, easing = MilkEasing.Decelerate), label = "subtab")
                    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((third.roundToPx() * frac).roundToInt(), 44.dp.roundToPx()) }.width(third).height(4.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(56.dp).height(4.dp).clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(c.primary))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
            PageColumn(scrolls[index], Modifier.weight(1f)) {
                when (SUB_TABS[index]) {
                    "watchlist" -> WatchlistTab(ctl, activeTab)
                    "watching" -> WatchingTab(ctl, activeTab)
                    "completed" -> CompletedTab(ctl, activeTab)
                    else -> HistoryTab(ctl, activeTab)
                }
            }
        }
    }
}

@Composable
private fun SelectionHeader(ctl: AppController, sc: String, caption: String, trailingWhenIdle: (@Composable () -> Unit)? = null) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val sel = ctl.selection?.takeIf { it.scope == sc }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        MilkText(if (sel != null) tr.t("selection.count", "length" to sel.keys.size) else caption, ty.caption, c.onSurfaceVariant, Modifier.weight(1f, fill = false))
        if (sel != null) {
            Row(Modifier.offset(x = 8.dp).width(120.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                MilkIconButton(MilkIcons.Selall, { ctl.toggleSelectAll(sc) }, size = 40.dp)
                MilkIconButton(MilkIcons.Del, { ctl.confirmDeleteSelection() }, size = 40.dp)
                MilkIconButton(MilkIcons.X2, { ctl.cancelSelection() }, size = 40.dp)
            }
        } else trailingWhenIdle?.invoke()
    }
}

@Composable
private fun SelectableRow(ctl: AppController, key: String, sc: String, activeTab: () -> String, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = Milk.colors
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .milkCombinedClickable(
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctl.toggleSelection(key, true, activeTab()) },
                onClick = { if (ctl.selection != null) ctl.toggleSelection(key, false, activeTab()) else onClick() },
            )
            .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (ctl.selection?.scope == sc) {
            Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, c.outline, CircleShape), contentAlignment = Alignment.Center) {
                MilkIcon(MilkIcons.Check, size = 16.dp, tint = c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyBlock(icon: Int, title: String) {
    EmptyState {
        MilkIcon(icon, size = 40.dp, tint = Milk.colors.onSurfaceVariant)
        MilkText(title, Milk.type.subtitle, Milk.colors.onSurface, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AnimeRowContent(ctl: AppController, a: Anime, sub: String, scope: RowScope) {
    val c = Milk.colors
    with(scope) {
        Poster(ctl, a, 52.dp)
        Column(Modifier.weight(1f)) {
            Clamp1(a.romaji, Milk.type.subtitle, c.onSurface)
            MilkText(sub, Milk.type.body, c.onSurfaceVariant)
        }
    }
}

@Composable
private fun WatchlistTab(ctl: AppController, activeTab: () -> String) {
    val tr = ctl.tr
    val list = ctl.persisted.watchlist.mapNotNull { ctl.animeOf(it) }
    if (list.isEmpty()) {
        EmptyBlock(MilkIcons.BmO, tr.t("collection.watchlistEmpty"))
        return
    }
    SelectionHeader(ctl, "watchlist", tr.t("collection.titleCount", "length" to list.size))
    MilkCard {
        list.forEachIndexed { i, a ->
            key(a.id) {
                if (i > 0) MilkDivider()
                val r = ctl.continueInfo(a)
                val sub = if (r != null) {
                    if (r.completed) tr.t("collection.completed")
                    else tr.t("collection.episodeOfTotal", "value" to (ctl.startedEpisodes(a).maxOrNull() ?: 0), "totalEpisodes" to a.totalEpisodes)
                } else tr.t("collection.notWatched", "totalEpisodes" to a.totalEpisodes)
                SelectableRow(ctl, "watchlist|${a.id}", "watchlist", activeTab, { ctl.openDetail(a.id) }) { AnimeRowContent(ctl, a, sub, this) }
            }
        }
    }
}

@Composable
private fun WatchingTab(ctl: AppController, activeTab: () -> String) {
    val tr = ctl.tr
    val list = ctl.continueWatchingList()
    if (list.isEmpty()) {
        EmptyBlock(MilkIcons.Play, tr.t("collection.noneWatching"))
        return
    }
    SelectionHeader(ctl, "watching", tr.t("collection.titleCount", "length" to list.size))
    MilkCard {
        list.forEachIndexed { i, a ->
            key(a.id) {
                if (i > 0) MilkDivider()
                SelectableRow(ctl, "watching|${a.id}", "watching", activeTab, { ctl.openDetail(a.id) }) { AnimeRowContent(ctl, a, ctl.continueLabel(a), this) }
            }
        }
    }
}

@Composable
private fun CompletedTab(ctl: AppController, activeTab: () -> String) {
    val tr = ctl.tr
    val time = ctl.time
    val list = remember(ctl.catalog, ctl.persisted.progress) { ctl.catalog.filter { ctl.isCompleted(it) } }
    if (list.isEmpty()) {
        EmptyBlock(MilkIcons.Check, tr.t("collection.noneCompleted"))
        return
    }
    SelectionHeader(ctl, "completed", tr.t("collection.titleCount", "length" to list.size))
    MilkCard {
        list.forEachIndexed { i, a ->
            key(a.id) {
                if (i > 0) MilkDivider()
                SelectableRow(ctl, "completed|${a.id}", "completed", activeTab, { ctl.openDetail(a.id) }) {
                    AnimeRowContent(ctl, a, tr.t("collection.episodesRating", "totalEpisodes" to a.totalEpisodes, "formatRating" to time.formatRating(a.rating)), this)
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(ctl: AppController, activeTab: () -> String) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val today0 = time.startOfToday()
    val items = remember(ctl.persisted.history) { ctl.persisted.history.sortedByDescending { it.watchedAt } }
    if (items.isEmpty()) {
        EmptyBlock(MilkIcons.Lib, tr.t("collection.historyEmpty"))
        return
    }
    val groups = remember(items, today0) {
        val g = linkedMapOf(
            "today" to mutableListOf<com.milkglass.app.core.model.HistoryEntry>(),
            "yesterday" to mutableListOf(),
            "longer" to mutableListOf()
        )
        items.forEach { h ->
            val d = Math.floor((today0 - h.watchedAt).toDouble() / DAY_MS).toInt() + 1
            val k = if (h.watchedAt >= today0) "today" else if (d <= 1) "yesterday" else "longer"
            g.getValue(k).add(h)
        }
        g
    }
    SelectionHeader(ctl, "history", tr.t("collection.historyHint", "length" to items.size)) {
        MilkIconButton(MilkIcons.Del, { ctl.confirmClearHistory() }, Modifier.offset(x = 8.dp), size = 40.dp)
    }
    groups.forEach { (k, list) ->
        if (list.isEmpty()) return@forEach
        key(k) {
            SectionHeader(tr.t(when (k) { "today" -> "common.today"; "yesterday" -> "time.yesterday"; else -> "collection.longer" }))
            MilkCard {
                list.forEachIndexed { i, h ->
                    val a = ctl.animeOf(h.id) ?: return@forEachIndexed
                    key("${h.id}.${h.episode}") {
                        if (i > 0) MilkDivider()
                        val thumbBg = remember(a.colorFrom, a.colorTo, h.episode) {
                            val from = parseCssColor(a.colorFrom) ?: Color.Black
                            val to = parseCssColor(a.colorTo) ?: Color.Black
                            cssLinearGradient((120 + h.episode * 17).toFloat(), listOf(from, to))
                        }
                        SelectableRow(ctl, "hist|${h.id}.${h.episode}", "history", activeTab, { ctl.playEpisode(h.id, h.episode) }) {
                            Box(
                                Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(thumbBg),
                                contentAlignment = Alignment.Center,
                            ) { MilkText("${h.episode}", ty.episodeThumb, Color.White) }
                            Column(Modifier.weight(1f)) {
                                Clamp1(a.romaji, ty.settingTitle, c.onSurface)
                                MilkText(
                                    "${tr.t("common.episodeAbbr")} ${h.episode} · ${time.formatTimeAgo(h.watchedAt)}",
                                    ty.caption, c.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

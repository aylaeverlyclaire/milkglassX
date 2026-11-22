package com.milkglass.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.ScheduleEntry
import com.milkglass.app.core.data.DAY_MS
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.ui.common.ButtonVariant
import com.milkglass.app.ui.common.Clamp1
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkIconButton
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.milkClickable

@Composable
private fun TimelineRail(color: androidx.compose.ui.graphics.Color, dotColor: androidx.compose.ui.graphics.Color, timeText: String, timeColor: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(start = 52.dp).drawBehind {
            drawRect(color, Offset(-1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
        },
    ) {
        content()
        MilkText(
            timeText, Milk.type.style(12f, 500, INHERIT, 0f, mono = true), timeColor,
            Modifier.offset(x = (-52).dp, y = 4.dp).width(44.dp), textAlign = TextAlign.End,
        )
        Box(Modifier.offset(x = (-4).dp, y = 8.dp).size(8.dp).clip(CircleShape).background(dotColor))
    }
}

private const val INHERIT = -1f

@Composable
fun ScheduleScreen(ctl: AppController, scroll: ScrollState) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val today = time.startOfDay(0)
    val days = remember(ctl.catalog, today) { (0 until 7).map { ctl.scheduleForDay(it) } }
    PageColumn(scroll) {
        (0 until 7).forEach { k ->
            key(k) {
                ScheduleDayColumn(ctl = ctl, dayOffset = k, items = days[k])
            }
        }
    }
}

@Composable
private fun ScheduleDayColumn(ctl: AppController, dayOffset: Int, items: List<ScheduleEntry>) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val ds = time.startOfDay(dayOffset)
    Column {
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MilkText(tr.locale.weekdaysLong[time.weekdayOf(ds)], ty.sectionTitle, c.onSurface, Modifier.alignByBaseline())
            MilkText(
                "${time.dayOfMonthOf(ds)} ${tr.locale.monthsShort[time.monthIndexOf(ds)]}${if (dayOffset == 0) " · " + tr.t("common.today") else ""}",
                ty.caption, c.onSurfaceVariant, Modifier.alignByBaseline(),
            )
        }
        if (items.isEmpty()) {
            MilkText(tr.t("schedule.noReleasesDay"), ty.caption, c.onSurfaceVariant)
        } else {
            // Hanya hari ke-0 yang perlu mengamati perpindahan posisi garis "Sekarang".
            val nowInsertIdx by remember(ctl, dayOffset, items) {
                derivedStateOf {
                    if (dayOffset != 0) -1
                    else {
                        val now = ctl.nowMs
                        val firstUpcoming = items.indexOfFirst { it.ts > now }
                        if (firstUpcoming < 0) items.size else firstUpcoming
                    }
                }
            }
            items.forEachIndexed { i, x ->
                if (dayOffset == 0 && i == nowInsertIdx) {
                    key("now") { NowRow(ctl) }
                }
                key("${x.a.id}.${x.ep}") {
                    ScheduleRow(ctl, x, last = i == items.size - 1)
                }
            }
            if (dayOffset == 0 && nowInsertIdx == items.size) {
                key("now") { NowRow(ctl) }
            }
        }
    }
}

@Composable
private fun NowRow(ctl: AppController) {
    val c = Milk.colors
    val nowTs = ctl.nowMs
    TimelineRail(c.primary, c.primary, ctl.time.formatClock(nowTs), c.primary) {
        Box(Modifier.height(28.dp))

        MilkText(ctl.tr.t("schedule.now"), Milk.type.style(12f, 500), c.primary, Modifier.offset(x = 16.dp, y = 4.dp))
    }
}

@Composable
private fun ScheduleRow(ctl: AppController, e: ScheduleEntry, last: Boolean) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val a = e.a
    val ok by remember(ctl, e.ts) { derivedStateOf { e.ts <= ctl.nowMs } }
    TimelineRail(
        if (ok) c.outlineVariant else c.primary, if (ok) c.outlineVariant else c.primary,
        time.formatClock(e.ts), c.onSurfaceVariant,
    ) {
        Box(Modifier.padding(start = 16.dp, bottom = if (last) 0.dp else 16.dp)) {
            MilkCard {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).milkClickable(pressDim = false) { ctl.openDetail(a.id) },
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Poster(ctl, a, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Clamp1(a.romaji, ty.subtitle, c.onSurface, Modifier.fillMaxWidth())
                            MilkText(
                                "${tr.t("common.episodeAbbr")} ${e.ep}${if (a.totalKnown && e.ep == a.totalEpisodes) tr.t("common.finalSuffix") else ""}",
                                ty.caption, c.onSurfaceVariant,
                            )
                            CountdownText(ctl, e.ts, hideWhenAired = true)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (ok) MilkButton(tr.t("schedule.watch"), { ctl.playEpisode(a.id, e.ep) }, variant = ButtonVariant.Primary, small = true)
                    }
                }
            }
        }
    }
}

package com.milkglass.app.ui.screens

import com.milkglass.app.core.data.VIBES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.GENRES
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkColumnGrid
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.ui.common.ButtonVariant
import com.milkglass.app.ui.common.Clamp1
import com.milkglass.app.ui.common.EmptyState
import com.milkglass.app.ui.common.Gutter
import com.milkglass.app.ui.common.LabelStrong
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkChip
import com.milkglass.app.ui.common.MilkIconButton
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.SectionHeader
import com.milkglass.app.ui.common.TopBar
import com.milkglass.app.ui.common.milkClickable

@Composable
private fun ChipRow(items: List<String>, activeIndex: (String) -> Boolean, label: (String) -> String, onClick: (String) -> Unit, modifier: Modifier = Modifier, edge: androidx.compose.ui.unit.Dp = Gutter) {
    LazyRow(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { items(items) { g -> MilkChip(label(g), activeIndex(g), { onClick(g) }) } }
}

@Composable
fun SearchScreen(ctl: AppController) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val fm = LocalFocusManager.current
    
    LaunchedEffect(ctl.searchFresh) { androidx.compose.runtime.withFrameNanos { }; runCatching { focus.requestFocus(); kb?.show() } }
    
    // Jaringan hanya dipanggil setelah pencarian dikonfirmasi, bukan tiap huruf.
    LaunchedEffect(ctl.submittedQuery) {
        if (ctl.submittedQuery.isNotBlank()) {
            fm.clearFocus(); kb?.hide()
            ctl.searchRemote(ctl.submittedQuery)
            ctl.searchUniversalIfNeeded(ctl.submittedQuery)
        }
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        TopBar(tr.t("search.title"), bare = true, leading = {
            MilkIconButton(MilkIcons.Back, { ctl.goBack() })
        })
        
        Row(
            Modifier.padding(start = Gutter, end = Gutter, top = 4.dp, bottom = 4.dp).fillMaxWidth().height(56.dp)
                .clip(RoundedCornerShape(28.dp)).background(c.scHigh).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MilkIcon(MilkIcons.Search, tint = c.onSurfaceVariant)
            Box(Modifier.weight(1f)) {
                
                val trailing = if (ctl.searchQuery.isEmpty()) 0.dp else 28.dp
                if (ctl.searchQuery.isEmpty()) MilkText(tr.t("search.placeholder"), ty.searchInput, c.onSurfaceVariant.copy(alpha = .72f), maxLines = 1)
                BasicTextField(
                    value = ctl.searchQuery,
                    onValueChange = { ctl.searchQuery = it; if (it.isBlank()) ctl.submittedQuery = "" },
                    modifier = Modifier.fillMaxWidth().padding(end = trailing).focusRequester(focus),
                    singleLine = true,
                    textStyle = ty.searchInput.copy(color = c.onSurface),
                    cursorBrush = SolidColor(c.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { ctl.submitSearch(); fm.clearFocus() }),
                )
                if (ctl.searchQuery.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).size(24.dp)
                            .milkClickable(pressDim = false) { ctl.searchQuery = ""; ctl.submittedQuery = "" },
                        contentAlignment = Alignment.Center,
                    ) { MilkIcon(MilkIcons.SearchClear, size = 16.dp, tint = Color.Unspecified) }
                }
            }
        }
        
        MilkText(tr.t("search.vibeLabel"), ty.caption, c.onSurfaceVariant, Modifier.padding(start = Gutter, top = 4.dp))
        ChipRow(
            VIBES, { it == ctl.searchVibe }, { ctl.enumLabel("vibe", it) },
            { ctl.searchVibe = if (ctl.searchVibe == it) "all" else it }, Modifier.padding(top = 8.dp),
        )
        
        ChipRow(
            listOf("all") + GENRES, { it == ctl.searchGenre }, { ctl.enumLabel("genre", it) },
            { ctl.searchGenre = it }, Modifier.padding(vertical = 8.dp),
            
        )
        
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { SearchResults(ctl) }
    }
}

@Composable
private fun SuggestionRow(text: String, onPick: () -> Unit, onFill: (() -> Unit)?) {
    val c = Milk.colors
    val ty = Milk.type
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).milkClickable(pressDim = false) { onPick() }
            .padding(horizontal = Gutter),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MilkIcon(MilkIcons.Search, size = 20.dp, tint = c.onSurfaceVariant)
        Box(Modifier.weight(1f)) { Clamp1(text, ty.body, c.onSurface) }
        if (onFill != null) {
            Box(Modifier.size(40.dp).milkClickable(pressDim = false) { onFill() }, contentAlignment = Alignment.Center) {
                MilkText("\u2196", ty.subtitle, c.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchResults(ctl: AppController) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val time = ctl.time
    val q = ctl.submittedQuery
    val typing = ctl.searchQuery.trim().isNotEmpty() && ctl.searchQuery.trim() != q.trim()
    if (typing) {
        // Sedang mengetik: hanya daftar saran ringan (tanpa kartu/poster/jaringan).
        val typed = ctl.searchQuery
        val suggestions by produceState(emptyList<String>(), typed) {
            delay(120)
            value = ctl.titleSuggestions(typed)
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp)) {
            // Baris pertama: cari persis teks yang diketik.
            SuggestionRow(typed.trim(), onPick = { ctl.runSearch(typed); }, onFill = null)
            suggestions.filterNot { it.equals(typed.trim(), ignoreCase = true) }.forEach { s ->
                SuggestionRow(s, onPick = { ctl.runSearch(s) }, onFill = { ctl.searchQuery = s })
            }
        }
        return
    }
    val results = ctl.searchCatalog()
    if (q.trim().isEmpty() && ctl.searchGenre == "all" && ctl.searchVibe == "all") {
        
        Column(
            Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (ctl.persisted.recentSearches.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(tr.t("search.recent"), right = { LabelStrong(tr.t("common.delete"), { ctl.clearRecentSearches() }) })
                    MilkColumnGrid(ctl.persisted.recentSearches) { t -> MilkChip(t, false, { ctl.runSearch(t) }, Modifier.fillMaxWidth()) }
                }
            }
            
            val tries = ctl.searchSuggestions()
                .filter { s -> ctl.persisted.recentSearches.none { it.trim().equals(s.trim(), ignoreCase = true) } }
            if (tries.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(tr.t("search.trySearch"))
                    MilkColumnGrid(tries) { t -> MilkChip(t, false, { ctl.runSearch(t) }, Modifier.fillMaxWidth()) }
                }
            }
        }
    } else if (results.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MilkText(tr.t("search.results", "count" to results.size), ty.caption, c.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                results.forEach { a ->
                    MilkCard {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 80.dp).milkClickable { ctl.openAndPlay(a.id) }
                                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Poster(ctl, a, 52.dp)
                            Column(Modifier.weight(1f)) {
                                Clamp1(a.romaji, ty.subtitle, c.onSurface)
                                Clamp1("${a.title} · ${a.studio}", ty.caption, c.onSurfaceVariant)
                                MilkText(
                                    "${ctl.genreSummary(a)} · ★ ${time.formatRating(a.rating)} · ${a.year}${if (time.isOngoing(a)) tr.t("search.airingSuffix") else ""}",
                                    ty.body, c.onSurfaceVariant,
                                )
                            }
                            MilkIcon(MilkIcons.Play, size = 24.dp, tint = c.primary)
                        }
                    }
                }
            }
        }
    } else {
        EmptyState {
            MilkIcon(MilkIcons.Search, size = 40.dp, tint = c.onSurfaceVariant)
            MilkText(tr.t("search.noResults", "escapeHtml" to q), ty.subtitle, c.onSurface, textAlign = TextAlign.Center)
            MilkButton(tr.t("search.reset"), { ctl.resetSearch() }, variant = ButtonVariant.Outlined)
        }
    }
}

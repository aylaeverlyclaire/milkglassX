package com.milkglass.app.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.app.Overlay
import com.milkglass.app.core.design.CssShadow
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkEasing
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.cssShadow
import com.milkglass.app.ui.common.ButtonVariant
import com.milkglass.app.ui.common.Gutter
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkText
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.ui.common.AppTextField
import com.milkglass.app.ui.common.EmptyState
import com.milkglass.app.ui.common.ChevronIcon
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkDivider
import com.milkglass.app.ui.common.MilkSwitch
import com.milkglass.app.ui.common.RadioRow
import com.milkglass.app.ui.common.SettingItem
import com.milkglass.app.ui.common.TabButton
import com.milkglass.app.ui.common.milkClickable
import kotlinx.coroutines.delay

private val NAV_ITEMS = listOf(
    Triple("home", MilkIcons.HomeO, MilkIcons.Home),
    Triple("schedule", MilkIcons.CalO, MilkIcons.Cal),
    Triple("social", MilkIcons.GroupO, MilkIcons.Group),
    Triple("collection", MilkIcons.LibO, MilkIcons.Lib),
)

@Composable
fun NavBar(ctl: AppController, activeTab: String, onSelect: (String) -> Unit) {
    val c = Milk.colors
    Row(
        Modifier.fillMaxWidth().background(c.surfaceContainer)
            .padding(start = 4.dp, end = 4.dp, top = 12.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        NAV_ITEMS.forEach { (name, outlined, filled) ->
            val active = name == activeTab
            Column(
                Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onSelect(name) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                        .then(if (active) Modifier.background(c.secondaryContainer) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    MilkIcon(if (active) filled else outlined, tint = if (active) c.onSecondaryContainer else c.onSurfaceVariant)
                }
                MilkText(
                    ctl.enumLabel("mainTab", name), Milk.type.navLabel,
                    if (active) c.onSurface else c.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun ToastHost(ctl: AppController) {
    val t = ctl.toast
    LaunchedEffect(t?.id) {
        if (t != null) { delay(t.ms); ctl.clearToast(t.id) }
    }
    if (t == null) return
    key(t.id) {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) { p.animateTo(1f, tween(180, easing = MilkEasing.EaseOut)) }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 96.dp)
                    .graphicsLayer { alpha = p.value; translationY = 8.dp.toPx() * (1f - p.value) }
                    .cssShadow(CssShadow(0.dp, 4.dp, 16.dp, Color.Black.copy(alpha = .3f)), shape = RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)).background(Milk.colors.onSurface)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    MilkText(t.text, Milk.type.toast, Milk.colors.surface, Modifier.weight(1f))
                    t.actionLabel?.let { label ->
                        MilkText(
                            label, Milk.type.labelStrong, Milk.colors.surface,
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Milk.colors.surface.copy(alpha = .16f))
                                .milkClickable(pressDim = false) { ctl.runToastAction(t.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OverlayHost(ctl: AppController) {
    val o = ctl.overlay ?: return
    val c = Milk.colors
    val isDialog = o is Overlay.ClearHistory || o is Overlay.DeleteSelection || o is Overlay.DeleteAccount || o is Overlay.SignOut
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f))
            .clickable(remember { MutableInteractionSource() }, null) { ctl.closeOverlay() },
        contentAlignment = if (isDialog) Alignment.Center else Alignment.BottomCenter,
    ) {
        val vh = maxHeight
        key(o) {
            if (isDialog) DialogBody(ctl, o) else SheetBody(ctl, o, vh)
        }
    }
}

@Composable
private fun DialogScaffold(title: String, actions: @Composable () -> Unit, body: @Composable () -> Unit) {
    val c = Milk.colors
    val ty = Milk.type
    Column(
        Modifier.imePadding().padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.scHigh)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        MilkText(title, ty.style(20f, 600, 26f), c.onSurface, Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp))
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) { actions() }
    }
}

@Composable
private fun DialogBody(ctl: AppController, o: Overlay) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    when (o) {
        is Overlay.DeleteAccount -> {
            var text by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
            DialogScaffold(
                tr.t("settings.deleteAccountTitle"),
                actions = {
                    TabButton(tr.t("common.cancel"), c.primary) { ctl.closeOverlay() }
                    TabButton(tr.t("settings.deleteAccount"), c.error) { ctl.deleteAccount(text) }
                },
            ) {
                MilkText(tr.t("settings.deleteAccountBody", "textField" to ""), ty.body, c.onSurfaceVariant)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppTextField(text, { text = it }, height = 56.dp, placeholder = tr.t("settings.typeDelete"), focusRequester = focus)
                    MilkText(ctl.deleteError ?: "", ty.style(13f, 400, 20f), c.error)
                }
            }
        }
        is Overlay.SignOut -> DialogScaffold(
            tr.t("auth.signOutTitle"),
            actions = {
                TabButton(tr.t("common.cancel"), c.primary) { ctl.closeOverlay() }
                TabButton(tr.t("common.exit"), c.error) { ctl.signOut() }
            },
        ) { MilkText(tr.t("auth.signOutBody"), ty.body, c.onSurfaceVariant) }
        is Overlay.ClearHistory -> DialogScaffold(
            tr.t("collection.clearHistoryTitle"),
            actions = {
                TabButton(tr.t("common.cancel"), c.primary) { ctl.closeOverlay() }
                TabButton(tr.t("common.delete"), c.error) { ctl.clearHistory() }
            },
        ) { MilkText(tr.t("collection.clearHistoryBody"), ty.body, c.onSurfaceVariant) }
        else -> {
            val sel = ctl.selection
            val n = sel?.keys?.size ?: 0
            val sc = sel?.scope
            DialogScaffold(
                tr.t(when (sc) { "watchlist" -> "selection.deleteFromWatchlist"; "completed" -> "selection.deleteFromCompleted"; else -> "selection.deleteFromHistory" }, "n" to n),
                actions = {
                    TabButton(tr.t("common.cancel"), c.primary) { ctl.closeOverlay() }
                    TabButton(tr.t("common.delete"), c.error) { ctl.deleteSelection() }
                },
            ) { MilkText(tr.t(when (sc) { "watchlist" -> "selection.watchlistBody"; "completed" -> "selection.completedBody"; else -> "selection.historyBody" }), ty.body, c.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SheetBody(ctl: AppController, o: Overlay, vh: androidx.compose.ui.unit.Dp) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(220, easing = MilkEasing.EaseOut)) }
    Column(
        Modifier.fillMaxWidth().imePadding().heightIn(max = vh * .88f)
            .graphicsLayer { translationY = 24.dp.toPx() * (1f - p.value); alpha = .6f + .4f * p.value }
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.scHigh)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Box(
            Modifier.padding(top = 12.dp, bottom = 4.dp).align(Alignment.CenterHorizontally)
                .width(32.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline.copy(alpha = .5f)),
        )
        fun title(t: String) = t
        val titleText = when (o) {
            is Overlay.LegalDocument -> ctl.tr.legalDoc(o.key)?.title ?: ""
            Overlay.QualityPicker -> tr.t("player.videoQuality")
            is Overlay.EditField -> tr.t(if (o.key == "nm") "profile.editName" else "profile.editUsername")
            is Overlay.SettingOptions -> tr.t("settings.theme")
            else -> ""
        }
        MilkText(titleText, ty.style(20f, 600, 26f), c.onSurface, Modifier.padding(horizontal = Gutter, vertical = 8.dp))
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(start = Gutter, end = Gutter, top = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (o) {
                is Overlay.LegalDocument -> {
                    val doc = ctl.tr.legalDoc(o.key)
                    Column(
                        Modifier.heightIn(max = vh * .56f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        doc?.sections?.forEachIndexed { i, sct ->
                            Column {
                                MilkText("${i + 1}. ${sct.heading}", ty.subtitle, c.onSurface)
                                MilkText(sct.body, ty.body, c.onSurfaceVariant)
                            }
                        }
                    }
                    MilkButton(tr.t("common.understood"), { ctl.closeOverlay() }, wide = true, variant = ButtonVariant.Tonal)
                }
                Overlay.QualityPicker -> {
                    listOf("Auto", "1080p", "720p", "480p").forEach { q ->
                        RadioRow(
                            ctl.persisted.settings.quality == q,
                            if (q == "Auto") tr.t("player.automatic") else q,
                            if (q == "Auto") tr.t("player.adaptsToNetwork") else null,
                        ) { ctl.setQuality(q) }
                    }
                }
                is Overlay.EditField -> {
                    val isName = o.key == "nm"
                    var text by remember { mutableStateOf(if (isName) ctl.meName else ctl.meHandle) }
                    AppTextField(text, { text = it }, maxLength = if (isName) 24 else 20)
                    MilkText(
                        ctl.editError ?: tr.t(if (isName) "profile.nameHelper" else "profile.usernameHelper"),
                        ty.caption, if (ctl.editError != null) c.error else c.onSurfaceVariant,
                        Modifier.heightIn(min = 16.dp).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
                    )
                    MilkButton(tr.t("common.save"), { ctl.saveEditField(o.key, text) }, wide = true)
                }
                is Overlay.SettingOptions -> {
                    listOf("system", "light", "dark").forEach { v ->
                        RadioRow(ctl.persisted.settings.theme == v, ctl.enumLabel("theme", v), null) { ctl.chooseSettingOption(o.key, v) }
                    }
                }
                else -> Unit
            }
        }
    }
}

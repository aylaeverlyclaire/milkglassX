package com.milkglass.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcon
import com.milkglass.app.core.design.MilkIcons
import com.milkglass.app.core.design.parseCssColor
import com.milkglass.app.ui.common.BadgeKind
import com.milkglass.app.ui.common.MilkBadge
import com.milkglass.app.ui.common.MilkButton
import com.milkglass.app.ui.common.MilkCard
import com.milkglass.app.ui.common.MilkDivider
import com.milkglass.app.ui.common.MilkText
import com.milkglass.app.ui.common.SectionHeader
import com.milkglass.app.ui.common.SettingsRow
import com.milkglass.app.ui.common.ToggleRow
import com.milkglass.app.ui.common.milkClickable

@Composable
private fun SettingsGroup(title: String, rows: List<@Composable () -> Unit>) {
    Column {
        SectionHeader(title)
        MilkCard(Modifier.padding(top = 8.dp)) {
            rows.forEachIndexed { i, r ->
                if (i > 0) MilkDivider()
                r()
            }
        }
    }
}

@Composable
fun ProfilePage(ctl: AppController, scroll: ScrollState) {
    val c = Milk.colors
    val ty = Milk.type
    val tr = ctl.tr
    val st = ctl.persisted.settings
    val meColor = ctl.users["me"]?.color?.let { parseCssColor(it) } ?: c.primary
    PageColumn(scroll) {

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(meColor), contentAlignment = Alignment.Center) {
                MilkText(ctl.meName.take(1).uppercase(), ty.style(26f, 600), Color.White)
            }
            Column {
                MilkText(ctl.meName, ty.title, c.onSurface)
                MilkText("@${ctl.meHandle}", ty.body, c.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MilkBadge(tr.t("badge.earlyAccess"), BadgeKind.Early, icon = MilkIcons.Star)
                }
            }
        }
        SettingsGroup(tr.t("settings.account"), listOf(
            { SettingsRow(tr.t("settings.name"), null, ctl.meName, { ctl.openEditField("nm") }) },
            { SettingsRow(tr.t("user.username"), null, "@${ctl.meHandle}", { ctl.openEditField("hd") }) },
        ))
        SettingsGroup(tr.t("settings.session"), listOf(
            { SettingsRow(tr.t("common.exit"), null, null, { ctl.confirmSignOut() }, trailing = { MilkIcon(MilkIcons.Out, tint = c.onSurfaceVariant) }) },
            { SettingsRow(tr.t("settings.deleteAccount"), null, null, { ctl.confirmDeleteAccount() }, titleColor = c.error, trailing = null) },
        ))
        MilkText(tr.t("settings.footer"), ty.caption, c.onSurfaceVariant, Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

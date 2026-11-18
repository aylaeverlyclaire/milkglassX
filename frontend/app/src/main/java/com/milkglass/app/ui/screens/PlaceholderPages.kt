package com.milkglass.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import com.milkglass.app.core.app.AppController
import com.milkglass.app.core.design.Milk
import com.milkglass.app.ui.common.EmptyState
import com.milkglass.app.ui.common.MilkText

@Composable
fun SocialPage(ctl: AppController, scroll: ScrollState) {
    val vh = LocalConfiguration.current.screenHeightDp.dp
    PageColumn(scroll) {
        EmptyState(topPadding = vh * .30f) {
            MilkText(ctl.tr.t("common.unavailable"), Milk.type.subtitle, Milk.colors.onSurface, textAlign = TextAlign.Center)
        }
    }
}

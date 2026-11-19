package com.milkglass.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.milkglass.app.core.design.Milk
import com.milkglass.app.core.design.MilkIcon

@Composable
fun SettingItem(
    icon: Int,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    featureOff: Boolean = false,
) {
    val c = Milk.colors
    val ty = Milk.type
    var m = modifier.fillMaxWidth().heightIn(min = 56.dp)
    m = if (featureOff) m.featureOffLook() else m.milkClickable(onClick = onClick)
    Row(m.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        MilkIcon(icon, tint = c.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            MilkText(title, ty.settingTitle, c.onSurface)
            if (subtitle != null) MilkText(subtitle, ty.settingSubtitle, c.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
fun MilkSwitch(active: Boolean) {
    val c = Milk.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.size(52.dp, 32.dp).clip(shape)
            .background(if (active) c.primary else c.scHighest)
            .border(2.dp, if (active) c.primary else c.outline, shape),
    ) {

        if (active) Box(Modifier.offset(x = 24.dp, y = 4.dp).size(24.dp).clip(CircleShape).background(c.onPrimary))
        else Box(Modifier.offset(x = 8.dp, y = 8.dp).size(16.dp).clip(CircleShape).background(c.outline))
    }
}

@Composable
fun RadioRow(active: Boolean, title: String, sub: String?, onClick: () -> Unit) {
    val c = Milk.colors
    val ty = Milk.type
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).milkClickable(pressDim = false, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (active) c.primary else c.onSurfaceVariant, CircleShape)) {
            if (active) Box(Modifier.align(Alignment.Center).size(12.dp).clip(CircleShape).background(c.primary))
        }
        Column(Modifier.weight(1f)) {
            MilkText(title, ty.style(14f), c.onSurface)
            if (sub != null) MilkText(sub, ty.caption, c.onSurfaceVariant)
        }
    }
}

@Composable
fun TabButton(text: String, color: Color, onClick: () -> Unit) {
    MilkText(
        text, Milk.type.button, color,
        Modifier.clip(RoundedCornerShape(999.dp)).milkClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun SettingsRow(
    title: String,
    sub: String?,
    value: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = Milk.colors.onSurface,
    trailing: (@Composable () -> Unit)? = { ChevronIcon() },
) {
    val c = Milk.colors
    val ty = Milk.type
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).milkClickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MilkText(title, ty.settingTitle, titleColor)
            if (sub != null) MilkText(sub, ty.settingSubtitle, c.onSurfaceVariant)
        }
        if (value != null) MilkText(value, ty.body, c.onSurfaceVariant)
        trailing?.invoke()
    }
}

@Composable
fun ToggleRow(title: String, sub: String?, on: Boolean, off: Boolean, onClick: () -> Unit) {
    val c = Milk.colors
    val ty = Milk.type
    var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    m = if (off) m.featureOffLook() else m.milkClickable(onClick = onClick)
    Row(m.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            MilkText(title, ty.settingTitle, c.onSurface)
            if (sub != null) MilkText(sub, ty.settingSubtitle, c.onSurfaceVariant)
        }
        MilkSwitch(on && !off)
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 52.dp,
    placeholder: String? = null,
    maxLength: Int = Int.MAX_VALUE,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val c = Milk.colors
    val ty = Milk.type
    val shape = RoundedCornerShape(12.dp)
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().height(height)
            .then(if (focused) Modifier.border(2.dp, c.primary, shape) else Modifier.border(1.dp, c.outline, shape))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) MilkText(placeholder, ty.style(16f), Color(0xFF757575), maxLines = 1)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onValueChange(it) },
            modifier = Modifier.fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = ty.style(16f).copy(color = c.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.onSurface),
        )
    }
}

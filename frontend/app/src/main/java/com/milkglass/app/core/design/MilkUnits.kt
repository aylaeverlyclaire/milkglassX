package com.milkglass.app.core.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Number.cssDp: Dp get() = toFloat().dp
val Number.cssSp: TextUnit get() = toFloat().sp

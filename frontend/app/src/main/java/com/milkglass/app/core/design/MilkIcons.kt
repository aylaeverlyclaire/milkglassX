package com.milkglass.app.core.design

import androidx.annotation.DrawableRes
import androidx.compose.runtime.remember
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.milkglass.app.R

object MilkIcons {
    @DrawableRes val Selall = R.drawable.ic_selall
    @DrawableRes val Readall = R.drawable.ic_readall
    @DrawableRes val Edit = R.drawable.ic_edit
    @DrawableRes val Like = R.drawable.ic_like
    @DrawableRes val Fav = R.drawable.ic_fav
    @DrawableRes val Pin = R.drawable.ic_pin
    @DrawableRes val Home = R.drawable.ic_home
    @DrawableRes val CalO = R.drawable.ic_cal_o
    @DrawableRes val GroupO = R.drawable.ic_group_o
    @DrawableRes val LibO = R.drawable.ic_lib_o
    @DrawableRes val HomeO = R.drawable.ic_home_o
    @DrawableRes val Back = R.drawable.ic_back
    @DrawableRes val Chev = R.drawable.ic_chev
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val Sort = R.drawable.ic_sort
    @DrawableRes val Check = R.drawable.ic_check
    @DrawableRes val Add = R.drawable.ic_add
    @DrawableRes val Play = R.drawable.ic_play
    @DrawableRes val Pause = R.drawable.ic_pause
    @DrawableRes val X2 = R.drawable.ic_x2
    @DrawableRes val Share = R.drawable.ic_share
    @DrawableRes val Verified = R.drawable.ic_verified
    @DrawableRes val Group = R.drawable.ic_group
    @DrawableRes val Send = R.drawable.ic_send
    @DrawableRes val Chat = R.drawable.ic_chat
    @DrawableRes val Person = R.drawable.ic_person
    @DrawableRes val Reply = R.drawable.ic_reply
    @DrawableRes val Copy = R.drawable.ic_copy
    @DrawableRes val Flag = R.drawable.ic_flag
    @DrawableRes val Del = R.drawable.ic_del
    @DrawableRes val Dl2 = R.drawable.ic_dl2
    @DrawableRes val Rst = R.drawable.ic_rst
    @DrawableRes val Info = R.drawable.ic_info
    @DrawableRes val Cal = R.drawable.ic_cal
    @DrawableRes val Lib = R.drawable.ic_lib
    @DrawableRes val Bell = R.drawable.ic_bell
    @DrawableRes val BellO = R.drawable.ic_bell_o
    @DrawableRes val Lock = R.drawable.ic_lock
    @DrawableRes val Crown = R.drawable.ic_crown
    @DrawableRes val Hd = R.drawable.ic_hd
    @DrawableRes val Fs = R.drawable.ic_fs
    @DrawableRes val More = R.drawable.ic_more
    @DrawableRes val Bm = R.drawable.ic_bm
    @DrawableRes val BmO = R.drawable.ic_bm_o
    @DrawableRes val Schedule = R.drawable.ic_schedule
    @DrawableRes val Block = R.drawable.ic_block
    @DrawableRes val Out = R.drawable.ic_out
    @DrawableRes val Dldone = R.drawable.ic_dldone
    @DrawableRes val Padd = R.drawable.ic_padd
    @DrawableRes val Gadd = R.drawable.ic_gadd
    @DrawableRes val Star = R.drawable.ic_star
    @DrawableRes val Down = R.drawable.ic_down
    @DrawableRes val BrandLogo = R.drawable.ic_brand_logo
    @DrawableRes val GoogleG = R.drawable.ic_google_g

    @DrawableRes val SearchClear = R.drawable.ic_search_clear

    val all: List<Pair<String, Int>> = listOf(
        "selall" to Selall,
        "readall" to Readall,
        "edit" to Edit,
        "like" to Like,
        "fav" to Fav,
        "pin" to Pin,
        "home" to Home,
        "cal-o" to CalO,
        "group-o" to GroupO,
        "lib-o" to LibO,
        "home-o" to HomeO,
        "back" to Back,
        "chev" to Chev,
        "search" to Search,
        "sort" to Sort,
        "check" to Check,
        "add" to Add,
        "play" to Play,
        "pause" to Pause,
        "x2" to X2,
        "share" to Share,
        "verified" to Verified,
        "group" to Group,
        "send" to Send,
        "chat" to Chat,
        "person" to Person,
        "reply" to Reply,
        "copy" to Copy,
        "flag" to Flag,
        "del" to Del,
        "dl2" to Dl2,
        "rst" to Rst,
        "info" to Info,
        "cal" to Cal,
        "lib" to Lib,
        "bell" to Bell,
        "bell-o" to BellO,
        "lock" to Lock,
        "crown" to Crown,
        "hd" to Hd,
        "fs" to Fs,
        "more" to More,
        "bm" to Bm,
        "bm-o" to BmO,
        "schedule" to Schedule,
        "block" to Block,
        "out" to Out,
        "dldone" to Dldone,
        "padd" to Padd,
        "gadd" to Gadd,
        "star" to Star,
        "down" to Down,
        "brand-logo" to BrandLogo,
        "google-g" to GoogleG,
    )
}

@Composable
fun MilkIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Milk.colors.onSurface,
) {
    val filter = remember(tint) { if (tint == Color.Unspecified) null else ColorFilter.tint(tint) }
    Image(
        painter = painterResource(id),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = filter,
    )
}

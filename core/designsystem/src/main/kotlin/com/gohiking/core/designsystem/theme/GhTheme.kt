package com.gohiking.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 品牌色与语义色（PRD 8.4 视觉规范）。
 * 计划线（去程 #2F80ED / 返程 #1B4F9C）、实际轨迹 #E24B4A、
 * 提醒绿 #639922、提醒橙 / 手动标记 #EF9F27。
 */
object GhColors {
    val PlanOutbound = Color(0xFF2F80ED)
    val PlanReturn = Color(0xFF1B4F9C)
    val Track = Color(0xFFE24B4A)
    val AlertAscent = Color(0xFF639922)
    val AlertDescent = Color(0xFFEF9F27)
    val ManualMarker = Color(0xFFEF9F27)
}

@Immutable
data class GhExtendedColors(
    val planOutbound: Color,
    val planReturn: Color,
    val track: Color,
    val alertAscent: Color,
    val alertDescent: Color,
    val manualMarker: Color,
)

private val LightColors = lightColorScheme()

private val DarkColors = darkColorScheme()

/** 主题入口（PRD 8.4：浅色 / 深色同时支持，跟随系统）。 */
@Composable
fun GhTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = GhExtendedColors(
        planOutbound = GhColors.PlanOutbound,
        planReturn = GhColors.PlanReturn,
        track = GhColors.Track,
        alertAscent = GhColors.AlertAscent,
        alertDescent = GhColors.AlertDescent,
        manualMarker = GhColors.ManualMarker,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalGhExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = GhTypography,
            content = content,
        )
    }
}

val LocalGhExtendedColors = androidx.compose.runtime.staticCompositionLocalOf {
    GhExtendedColors(
        planOutbound = GhColors.PlanOutbound,
        planReturn = GhColors.PlanReturn,
        track = GhColors.Track,
        alertAscent = GhColors.AlertAscent,
        alertDescent = GhColors.AlertDescent,
        manualMarker = GhColors.ManualMarker,
    )
}

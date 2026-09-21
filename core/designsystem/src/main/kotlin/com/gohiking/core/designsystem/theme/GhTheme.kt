package com.gohiking.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 品牌色与语义色（PRD 8.4 视觉规范）。
 * 计划线路（去程 #2F80ED / 返程 #1B4F9C）、实际轨迹 #E24B4A、
 * 提醒绿 #639922、提醒橙 / 手动标记 #EF9F27。
 * 中性色与交互色对齐 prototype/styles.css 的设计基线（P-01~P-18 交互原型）。
 */
object GhColors {
    val PlanOutbound = Color(0xFF2F80ED)
    val PlanReturn = Color(0xFF1B4F9C)
    val Track = Color(0xFFE24B4A)
    val AlertAscent = Color(0xFF639922)
    val AlertDescent = Color(0xFFEF9F27)
    val ManualMarker = Color(0xFFEF9F27)

    // 界面中性色（prototype/styles.css :root）
    val Bg = Color(0xFFF4F6F8)
    val Surface = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF7F8FA)
    val TextPrimary = Color(0xFF1B1F24)
    val TextSecondary = Color(0xFF6B7280)
    val TextTertiary = Color(0xFF9CA3AF)
    val Line = Color(0xFFE5E7EB)
    val Line2 = Color(0xFFEEF0F3)
    val Primary = Color(0xFF2F80ED)
    val Danger = Color(0xFFE24B4A)
    val Ok = Color(0xFF639922)

    // 标签底色（tag.b/g/o/r）
    val TagBlueBg = Color(0xFFE8F1FE)
    val TagBlueFg = Color(0xFF1B4F9C)
    val TagGreenBg = Color(0xFFEDF6E6)
    val TagGreenFg = Color(0xFF4A7318)
    val TagOrangeBg = Color(0xFFFDF1DF)
    val TagOrangeFg = Color(0xFFA36A12)
    val TagRedBg = Color(0xFFFBEAEA)
    val TagRedFg = Color(0xFFB23A39)
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

private val LightColors = lightColorScheme(
    primary = GhColors.Primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = GhColors.TagBlueBg,
    onPrimaryContainer = GhColors.TagBlueFg,
    secondary = GhColors.TextSecondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = GhColors.Surface2,
    onSecondaryContainer = GhColors.TextPrimary,
    tertiary = GhColors.Ok,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = GhColors.TagGreenBg,
    onTertiaryContainer = GhColors.TagGreenFg,
    error = GhColors.Danger,
    onError = Color(0xFFFFFFFF),
    errorContainer = GhColors.TagRedBg,
    onErrorContainer = GhColors.TagRedFg,
    background = GhColors.Bg,
    onBackground = GhColors.TextPrimary,
    surface = GhColors.Surface,
    onSurface = GhColors.TextPrimary,
    surfaceVariant = GhColors.Surface2,
    onSurfaceVariant = GhColors.TextSecondary,
    outline = GhColors.Line,
    outlineVariant = GhColors.Line2,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC0F7),
    onPrimary = Color(0xFF0A2A55),
    primaryContainer = Color(0xFF1B4F9C),
    onPrimaryContainer = Color(0xFFE8F1FE),
    error = Color(0xFFF08A89),
    background = Color(0xFF14171C),
    surface = Color(0xFF1B1F24),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF242932),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF3A4048),
    outlineVariant = Color(0xFF2A2E36),
)

/** 原型基线：卡片/按钮圆角统一 12dp（--r-card / --r-btn）。 */
private val GhShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** 主题入口（PRD 8.4：浅色 / 深色同时支持，跟随系统）。 */
@Composable
fun GhTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
            shapes = GhShapes,
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
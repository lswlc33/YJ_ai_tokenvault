package com.lc33.tokenvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 配色模式。刻意不直接用 MIUIX 的 `ColorSchemeMode`：
 * 设置项要持久化、要进备份白名单，不能让一个实验期 UI 库的枚举渗到数据层。
 * MIUIX 的映射只在 `ui/miuix/AppTheme.kt` 里做一次。
 */
enum class AppColorSchemeMode {
    System,
    Light,
    Dark,
    MonetSystem,
    MonetLight,
    MonetDark,
}

/**
 * 一处定义间距、圆角、排版与动效时长（计划.md §13.3）。
 *
 * 规矩：页面代码里禁止出现 `Color(0xFF…)` 和裸 `fontSize`，一律走 token。
 */
@Immutable
data class AppTokens(
    /** 页面左右边距 */
    val screenPadding: Dp = 16.dp,
    /** 卡片之间、行之间的垂直间距 */
    val itemSpacing: Dp = 8.dp,
    /** 区块之间的垂直间距 */
    val sectionSpacing: Dp = 20.dp,
    val cardRadius: Dp = 16.dp,
    val dialogRadius: Dp = 24.dp,
    /** 触控目标下限，不允许用 Modifier.scale() 把交互控件缩小 */
    val minTouchTarget: Dp = 48.dp,
    /** 状态圆点直径 */
    val statusDotSize: Dp = 8.dp,
    /**
     * 密钥、模型 id、URL 用的等宽字族。
     *
     * 计划.md §13.3 要求最终在 `res/font` 里放一份自带字体（避免各设备的
     * "monospace" 落到不同字形上），M3 做金库 UI 时换掉；在那之前用平台等宽，
     * 但入口已经收在 token 里，页面代码不会出现字体字面量。
     */
    val monoFontFamily: FontFamily = FontFamily.Monospace,
    /** 短动效（状态切换、展开回遮） */
    val animShort: Int = 150,
    /** 中等动效（页面内容切换） */
    val animMedium: Int = 250,
)

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }

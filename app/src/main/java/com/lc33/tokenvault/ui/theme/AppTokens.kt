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
 *
 * **枚举的声明顺序就是 `R.array.color_scheme_modes` 的顺序**：设置页的下拉是按下标选的，
 * 两边错位的表现是"选了深色得到壁纸取色"。`ArchitectureRulesTest` 会比这两个数量。
 */
enum class AppColorSchemeMode {
    System,
    Light,
    Dark,
    MonetSystem,
    MonetLight,
    MonetDark,
    ;

    companion object {
        /**
         * 从 `boot.themeMode` 的存储值还原。
         *
         * 认不出来时回落 [System]，而且**不改写存储**——照红线 3 的同一条道理：
         * "发现与编译期常量不一致就改写存储值"是永久丢配置的定时炸弹。这里改写的代价比
         * KDF 参数小得多（只是一个配色），但同一个降级到旧版本的用户会因此被静默改掉设置。
         */
        fun fromStorage(value: String): AppColorSchemeMode =
            entries.firstOrNull { it.name == value } ?: System
    }
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
    /**
     * 带 FAB 的列表末尾要留出的空白。
     *
     * FAB 不是 inset，`Scaffold` 不会替列表让位，所以最后一行会被压在 FAB 底下——
     * 而那一行往往是"删除"这种不能误触的操作。取 FAB 直径 + 两倍间距。
     */
    val fabListBottomSpace: Dp = 88.dp,
)

val LocalAppTokens = staticCompositionLocalOf { AppTokens() }

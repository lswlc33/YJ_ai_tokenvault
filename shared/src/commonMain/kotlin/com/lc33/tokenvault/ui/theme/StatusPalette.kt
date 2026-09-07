package com.lc33.tokenvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 状态色的唯一来源（计划.md 红线 17）。
 *
 * 全应用同一状态只有一套文案（strings.xml）和一套颜色（这里）。
 * 状态必须同时用颜色和文字表达，所以 `StatusDot` 永远带文字，
 * 不允许出现"只靠色点"的地方。
 */
@Immutable
data class StatusPalette(
    /** 可用 */
    val ok: Color,
    /** 余额不足 / 配置错误 / 需客户端伪装 —— 需要用户处理但密钥本身没死 */
    val warn: Color,
    /** 密钥无效 / 无权限 */
    val error: Color,
    /** 未探测 */
    val neutral: Color,
    /** 次要信息（"上次成功于 …"这类副文案） */
    val muted: Color,
)

private val LightStatusPalette = StatusPalette(
    ok = Color(0xFF1E8E3E),
    warn = Color(0xFFB06000),
    error = Color(0xFFC5221F),
    neutral = Color(0xFF6B7280),
    muted = Color(0xFF8A9099),
)

private val DarkStatusPalette = StatusPalette(
    ok = Color(0xFF57C46B),
    warn = Color(0xFFE8A33D),
    error = Color(0xFFF06B62),
    neutral = Color(0xFF9AA1AC),
    muted = Color(0xFF7C838E),
)

fun statusPaletteFor(dark: Boolean): StatusPalette = if (dark) DarkStatusPalette else LightStatusPalette

val LocalStatusPalette = staticCompositionLocalOf { LightStatusPalette }

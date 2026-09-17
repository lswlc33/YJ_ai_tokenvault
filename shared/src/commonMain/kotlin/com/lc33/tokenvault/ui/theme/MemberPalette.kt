package com.lc33.tokenvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 会员卡的配色（设置页顶部那张卡、会员介绍页）。
 *
 * 单独开一个调色板而不是复用 `MiuixTheme.colorScheme.primary`：会员卡要的是**固定的蓝**，
 * 而主色会随配色模式（尤其是 Monet 取色）变——在壁纸是红色的手机上，"蓝色大卡标识"
 * 会变成红色，那个身份标识就不成立了。所以这一组颜色写死在这里，两端各一套明暗。
 *
 * 页面代码仍然不出现 `Color(0xFF…)`：颜色只在本文件与 `ui/miuix/` 里定义。
 */
@Immutable
data class MemberPalette(
    /** 会员态卡面渐变（左上 → 右下）。 */
    val gradientStart: Color,
    val gradientEnd: Color,
    /** 会员态卡面上的文字与图标色。 */
    val onMember: Color,
    /** 金色点缀：角标、分隔线、"尊贵"那类装饰用它。 */
    val accent: Color,
    /** 普通用户那张卡的底色。刻意比会员态素净，让两者一眼可分。 */
    val idleBackground: Color,
    val idleContent: Color,
)

private val LightMemberPalette = MemberPalette(
    gradientStart = Color(0xFF1B4FD8),
    gradientEnd = Color(0xFF4E8DF7),
    onMember = Color(0xFFFFFFFF),
    accent = Color(0xFFF3C463),
    idleBackground = Color(0xFFE8EEF9),
    idleContent = Color(0xFF27406B),
)

private val DarkMemberPalette = MemberPalette(
    gradientStart = Color(0xFF14307E),
    gradientEnd = Color(0xFF2E5FC4),
    onMember = Color(0xFFF2F6FF),
    accent = Color(0xFFE9B65A),
    idleBackground = Color(0xFF1B2740),
    idleContent = Color(0xFFB9C8E4),
)

fun memberPaletteFor(dark: Boolean): MemberPalette = if (dark) DarkMemberPalette else LightMemberPalette

val LocalMemberPalette = staticCompositionLocalOf { LightMemberPalette }

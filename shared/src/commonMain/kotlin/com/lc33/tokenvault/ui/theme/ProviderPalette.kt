package com.lc33.tokenvault.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 供应商色块：八个手选色 + 一家一个的生成色。
 *
 * 与 [StatusPalette] 分开：状态色表达"好不好"，这一组只是让用户在长列表里认出某一行，
 * 不承担任何语义。`providers.color` 存的是手选色的下标（红线 16 的唯一入口是编辑页的
 * 颜色选择行），**NULL 表示"没选过，用生成色"**。取模访问，所以下标越界不会崩。
 *
 * **色块数量必须与 `provider_colors` 那个 string-array 的条目数一致**：编辑页是按
 * 下标选色的，少一项就是"选第 8 个颜色得到第 1 个"。`ArchitectureRulesTest` 守着这条。
 * 编辑页下拉里那一排**多出来的「自动」项不在这个数组里**（它在 8 个下标之后），所以
 * 加它不动这条对应关系。
 *
 * ## 为什么要生成色
 *
 * 手选色只有 8 个，而实际有十几家供应商是常态：撞色之后趋势图上两条线一个颜色，
 * 图例就得逐行去对名字——这个页要回答的问题（"这条线是谁"）就答不上了。生成色按
 * [providerId] 算，**同一家永远同一个色**，且不依赖"这一页上有几家"这种局部信息，
 * 所以在列表页、详情页、余额趋势、模型变化四处看到的是同一个身份色。
 */
@Immutable
data class ProviderPalette(
    val swatches: List<Color>,

    /** 生成色的饱和度与亮度档：明暗两套不同，保证线在两种背景下都看得清。 */
    val generatedSaturation: Float,
    val generatedLightness: Float,
) {
    fun swatchFor(index: Int): Color = swatches[((index % swatches.size) + swatches.size) % swatches.size]

    /**
     * 一家站点的**身份色**：手选过用手选色，没手选过用生成色。
     *
     * [providerId] 只在生成色这条分支上用得到，所以传 0（新建还没落库）也安全。
     */
    fun colorFor(providerId: Long, explicitIndex: Int?): Color =
        explicitIndex?.let(::swatchFor) ?: generatedFor(providerId)

    /**
     * 按 id 生成的色相：黄金角步进。
     *
     * 黄金角（约 137.508°）是无理数比例的角步，**连续自增的 id 天然把色相环铺得最开**——
     * id 1..10 出来的色相是 137/275/52/190/327/105/242/20/157/295，正好一圈都不重。
     * 库里的 id 就是自增的，所以这一条比"随机撒一把再避开撞色"更稳：它不看邻居、
     * 不会因为多了一家而把别人家的颜色改掉。
     *
     * 亮度按 id 分三档轻微浮动：万一两家 id 差到色相几乎重合（差 144 的整数倍才会），
     * 深浅不同还分得出。
     */
    fun generatedFor(providerId: Long): Color {
        val hue = (((providerId * GOLDEN_ANGLE_DEGREES) % 360f) + 360f) % 360f
        val lightness = (generatedLightness + (providerId.mod(3) - 1L) * LIGHTNESS_STEP).coerceIn(0.15f, 0.85f)
        return Color.hsl(hue, generatedSaturation, lightness)
    }

    private companion object {
        const val GOLDEN_ANGLE_DEGREES = 137.508f
        const val LIGHTNESS_STEP = 0.06f
    }
}

/** 备选色的数量。颜色名走 `provider_colors` 资源，两边必须一致（架构测试盯着）。 */
const val PROVIDER_COLOR_COUNT = 8

private val LightSwatches = listOf(
    Color(0xFF3B76F0),
    Color(0xFF17A2A2),
    Color(0xFF7A5AF0),
    Color(0xFFD9720B),
    Color(0xFFC63B6E),
    Color(0xFF2E9E52),
    Color(0xFF5B6B7C),
    Color(0xFF9A6A1F),
)

private val DarkSwatches = listOf(
    Color(0xFF6F9BF5),
    Color(0xFF44C2C2),
    Color(0xFF9E88F5),
    Color(0xFFE99A44),
    Color(0xFFE0708F),
    Color(0xFF5FBE7C),
    Color(0xFF8996A5),
    Color(0xFFC49A4F),
)

/**
 * 「自动」这一档在编辑页颜色下拉里的下标——排在八个手选色**之后**。
 *
 * 不在 `provider_colors` 里，所以那条"数组长度 == [PROVIDER_COLOR_COUNT]"的架构约束
 * 不因它改变；它也不对应任何一个存进库的下标（选它就是把 `providers.color` 写回 NULL）。
 */
const val PROVIDER_AUTO_COLOR_INDEX = PROVIDER_COLOR_COUNT

fun providerPaletteFor(dark: Boolean): ProviderPalette = ProviderPalette(
    swatches = if (dark) DarkSwatches else LightSwatches,
    // 亮色下压得深一点，线才压得住白底；暗色下提亮，同理。饱和度稍高是为了十几家各认各的。
    generatedSaturation = if (dark) 0.55f else 0.62f,
    generatedLightness = if (dark) 0.68f else 0.45f,
)
val LocalProviderPalette = androidx.compose.runtime.staticCompositionLocalOf {
    ProviderPalette(LightSwatches, generatedSaturation = 0.62f, generatedLightness = 0.45f)
}

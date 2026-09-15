// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.lc33.tokenvault.ui.miuix.liquid

// 自 miuix-blur 公开 API 拼装的玻璃卡片：背景模糊 + vibrancy + 动态光斑。
// 底层只依赖 miuix-blur 与本包已有的 vendor 工具（升级 MIUIX 时与其它 liquid 文件一起对照示例同步）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.ui.miuix.AppLayerBackdrop
import com.lc33.tokenvault.ui.miuix.LocalAppDarkTheme
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.blur.sensor.rememberDeviceTilt

/**
 * 会跟着设备姿态轻微游移的主光斑中心。**dynamic 的落点**：光斑位置由重力方向插值，
 * 手机一倾斜高光就换个角度扫过卡片，这是静态渐变给不了的"玻璃在反光"的观感。
 * 落在一个小方格内而不是满卡游走——幅度大了就成了弹珠，不是光。
 */
@Composable
private fun dynamicLightCenter(baseX: Float, baseY: Float): Offset {
    val tilt by rememberDeviceTilt()
    return remember(tilt, baseX, baseY) {
        // gravityX/Y 大致在 [-1, 1]；压缩到 ±0.12 / ±0.08，只做"轻推"。
        Offset(
            x = (baseX + tilt.gravityX * 0.12f).coerceIn(0.2f, 0.8f),
            y = (baseY - tilt.gravityY * 0.08f).coerceIn(0.15f, 0.7f),
        )
    }
}

/**
 * 玻璃面之上的两层绘制：
 *
 * 1. **动态光晕**——主色为圆心、向外渐隐的径向渐变，圆心随设备姿态微移；
 * 2. **镜面高光**——从顶上洒下来的一道白光，模拟环境光擦亮玻璃。
 *
 * 两处都用**多段渐变**而不是"一色到透明"：两段式在半径/终点处会留下一圈看得见的
 * 边界（实测在卡片右下角会显出一条斜的暗边，像采样错位）。多段过渡把边界磨掉。
 * 深浅色各给一档透明度：深色下白光提亮玻璃，浅色下收敛，否则白底上一片惨白。
 */
private fun DrawScope.drawGlassSurface(
    accent: Color,
    lightCenter: Offset,
    isDark: Boolean,
) {
    val glow = if (isDark) 0.30f else 0.18f
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = glow),
                accent.copy(alpha = glow * 0.45f),
                Color.Transparent,
            ),
            center = Offset(size.width * lightCenter.x, size.height * lightCenter.y),
            radius = size.maxDimension * 0.9f,
        ),
    )
    val sheen = if (isDark) 0.16f else 0.08f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = sheen),
                Color.White.copy(alpha = sheen * 0.35f),
                Color.Transparent,
            ),
        ),
    )
}

/**
 * **玻璃材质卡**：对 [blurBackdrop] 录制的画面做实时模糊与 vibrancy 提饱和，
 * 再叠动态主色光晕与镜面高光。
 *
 * 与 [com.lc33.tokenvault.ui.miuix.AppAccentCard] 的实色主色底是两种气质：那一张是
 * "按钮式"的强调块，这一张是"玻璃片"——高级感来自背后的画面透过磨砂玻璃隐约可见，
 * 而不是更浓的色块。首页余额卡用它。
 *
 * ## 铁律：卡片**不能**落在 [blurBackdrop] 录制的子树里
 *
 * 被录制的层一旦包含卡片本身，录下的画面就包含"卡片画这份画面"这件事——
 * 渲染树的深度每帧增加一层，几秒后 RenderThread 栈溢出、进程 SIGSEGV
 * （2026-09-15 的闪退：tombstone 512 帧 `RenderNode::prepareTreeImpl` 递归，
 * "stack pointer is close to top of stack; likely stack overflow"）。
 *
 * 正确形态是让卡片与该层**平级**：
 *
 * ```
 * Box {
 *     AmbientLayer(Modifier.fillMaxSize().appLayerBackdrop(backdrop))  // 被录制：只有背景
 *     Content()                        // 不被录制
 *         .GlassCard(backdrop)         // 采样上面那层，自己在它外面
 * }
 * ```
 *
 * [com.lc33.tokenvault.ui.miuix.LocalAppBottomBarInset] 那份 Shell 级内容 backdrop 同理
 * **不可**传给页面内的卡片：页面内容整个都在它的子树里。它只给底栏那种与内容平级的
 * 浮层用（底栏在外层 Scaffold，不在录制范围内）。
 *
 * 另外两件事由调用方与运行环境决定：
 *
 * 1. 被采样的那层要挂 [Modifier.appLayerBackdrop]（`ui/shell` 或页面自己挂）；
 * 2. 背景模糊依赖运行时着色器；不支持（老设备 / 桌面端）时 `blur` 会整段跳过，
 *    卡片只剩动态光晕与高光，可读性仍在。
 *
 * @param blurBackdrop 背景层句柄。null 退回实色主色底（测试 / 预览）。
 * @param contentPadding 卡片内边距。默认与 [com.lc33.tokenvault.ui.miuix.AppAccentCard] 一致。
 */
@Composable
fun AppGlassCard(
    blurBackdrop: AppLayerBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalAppTokens.current.cardRadius),
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = LocalAppDarkTheme.current
    val accent = appPrimaryColor
    val lightCenter = dynamicLightCenter(baseX = 0.3f, baseY = 0.25f)

    val surfaceModifier = if (blurBackdrop != null) {
        modifier.drawBackdrop(
            backdrop = blurBackdrop.backdrop,
            shape = { shape },
            effects = {
                // vibrancy：提饱和 + 略压亮度，模糊后的背景色不至于糊成一片灰；
                // 再补一层细颗粒噪点抑制色带。
                vibrancy()
                blur(24.dp.toPx(), 24.dp.toPx())
                noiseDither(0.6f)
            },
            onDrawSurface = { drawGlassSurface(accent, lightCenter, isDark) },
        )
    } else {
        // 没有可采样的背景（二级页未挂 backdrop / 降级路径）：退回实色主色底，
        // 光晕照样画——它不依赖背景内容。
        modifier.background(accent, shape)
    }

    // 文字颜色由调用方像 AppAccentCard 一样显式传 appOnPrimaryColor——AppText
    // 不读隐式 contentColor，模糊背景的明度也不可控，显式传最稳。
    Box(modifier = surfaceModifier) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

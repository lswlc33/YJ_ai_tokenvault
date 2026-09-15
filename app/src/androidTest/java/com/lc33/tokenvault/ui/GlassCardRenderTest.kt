package com.lc33.tokenvault.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTheme
import com.lc33.tokenvault.ui.miuix.appLayerBackdrop
import com.lc33.tokenvault.ui.miuix.appOnPrimaryColor
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.liquid.AppGlassCard
import com.lc33.tokenvault.ui.miuix.rememberAppLayerBackdrop
import com.lc33.tokenvault.ui.theme.AppColorSchemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 玻璃卡的**渲染回归测试**（必须跑在设备上，JVM 单测不渲染，抓不到这类问题）。
 *
 * 钉住的是 2026-09-15 那次闪退的模式：`AppGlassCard` 采样了一份**包含它自己**的
 * 背景层时，录下的画面里又包含"卡片画这份画面"，渲染树每帧翻倍增长，几秒内
 * RenderThread 栈溢出（tombstone：512 帧 `RenderNode::prepareTreeImpl` 递归、
 * "likely stack overflow"），进程直接 SIGSEGV。
 *
 * 测试做三件事，缺一不可：
 *
 * 1. 按**允许的形态**搭建：背景层被录制、玻璃卡是它的兄弟节点；
 * 2. 让背景层**每帧都在变**（无限动画），强制几十次绘制——静态画面只画一两帧，
 *    自引用的深度涨不起来，测了等于没测；
 * 3. 手动时钟推进帧，跑完再断言卡片还在。
 *
 * 回归时会怎么表现：进程在 RenderThread 崩掉，测试进程一起死，整个 run 失败——
 * 这正是我们要的信号（它不可能"优雅地断言失败"，原生栈溢出不给这个机会）。
 */
@RunWith(AndroidJUnit4::class)
class GlassCardRenderTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `玻璃卡采样平级背景层可以连续渲染`() {
        // 无限动画 + 手动时钟：这样每一帧都有真实绘制，且 waitForIdle 不会被
        // 永不结束的动画卡住（自动时钟下无限动画正是会挂住 waitForIdle 的那种情况）。
        rule.mainClock.autoAdvance = false
        rule.setContent {
            AppTheme(mode = AppColorSchemeMode.Light) {
                val backdrop = rememberAppLayerBackdrop()
                // 被录制的一层：**只有背景渐变，不含玻璃卡**。
                // 动画挂在它身上，保证每一帧的内容都不同、都要重新录制。
                val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse),
                    label = "pulseAlpha",
                )
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(appPrimaryColor, Color.Transparent),
                                ),
                            )
                            .alpha(pulse)
                            .appLayerBackdrop(backdrop),
                    )
                    // 卡片与上面那层平级 → 它不会出现在被录制的画面里，没有自引用。
                    AppGlassCard(
                        blurBackdrop = backdrop,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        AppText(
                            text = GLASS_TEXT,
                            style = AppTextStyle.Title,
                            color = appOnPrimaryColor,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(GLASS_TEXT).assertIsDisplayed()

        // 40 帧足够暴露自引用：那种情况下渲染树每帧翻倍，十帧上下就爆栈。
        repeat(40) {
            rule.mainClock.advanceTimeBy(16)
            rule.waitForIdle()
        }
        rule.onNodeWithText(GLASS_TEXT).assertIsDisplayed()
    }

    private companion object {
        const val GLASS_TEXT = "glass-card-render-test"
    }
}

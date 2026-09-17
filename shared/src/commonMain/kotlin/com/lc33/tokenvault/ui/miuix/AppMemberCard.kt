package com.lc33.tokenvault.ui.miuix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.platform.Haptics
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalMemberPalette
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Surface

/**
 * 设置页顶部那张会员卡（娱乐功能）。
 *
 * 两个形态共用一个组件，因为它们是**同一张卡的两种状态**：普通用户是素净的蓝灰底、
 * 提示"点击查看"；会员是蓝色渐变 + 金角标，是那个"身份标识"本身。
 *
 * 两个手势，职责不重叠：
 * - **轻点**：都进会员介绍页。会员也要能进去看（那里有"取消"按钮）。
 * - **长按 5 秒**（仅会员）：直接退回普通用户。为什么定 5 秒而不是系统默认的 500 毫秒：
 *   这一下会让"会员"消失，而卡片本身是可点的、长按在触屏上又很容易被误触发（按住拖动、
 *   手指出汗都会）——把时限拉到 5 秒，加上底部那道光条作为进度反馈，误触不可能发生。
 *
 * 进度条不是装饰：没有它，用户按住 3 秒松手会觉得"这功能坏了"。
 */
@Composable
fun AppMemberCard(
    member: Boolean,
    title: String,
    subtitle: String,
    /** 会员态右上角的角标文案（"会员" / "MEMBER"）。普通用户态不画它。 */
    badge: String,
    /** 普通用户态卡片底部那行小字（"点击查看会员特权"）。 */
    hint: String,
    onOpen: () -> Unit,
    /** 长按满时长时触发。只在 [member] 为真时有效。 */
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Int = MEMBER_HOLD_TO_REVERT_MS,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalMemberPalette.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    // 长按已经触发过的那一次轻点要丢掉：5 秒满了之后松手，不该顺带跳进会员页。
    var holdFired by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(tokens.cardRadius)
    val gesture = Modifier.pointerInput(member, onOpen, onRevert, holdDurationMs) {
        detectTapGestures(
            onPress = {
                holdFired = false
                if (member) {
                    val hold = scope.launch {
                        progress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(holdDurationMs, easing = LinearEasing),
                        )
                        holdFired = true
                        Haptics.impact()
                        onRevert()
                    }
                    tryAwaitRelease()
                    hold.cancel()
                    progress.snapTo(0f)
                } else {
                    tryAwaitRelease()
                }
            },
            onTap = { if (!holdFired) onOpen() },
        )
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = gesture,
            shape = shape,
            color = if (member) palette.gradientStart else palette.idleBackground,
        ) {
            // 渐变铺在底色之上：Surface 只吃单色，会员态那层渐变由这里补。
            Box(
                modifier = Modifier
                    .background(
                        if (member) {
                            Brush.linearGradient(listOf(palette.gradientStart, palette.gradientEnd))
                        } else {
                            Brush.linearGradient(
                                listOf(palette.idleBackground, palette.idleBackground),
                            )
                        },
                    )
                    .padding(tokens.sectionSpacing),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                    ) {
                        AppIconTint(
                            icon = AppIcon.Member,
                            modifier = Modifier.size(30.dp),
                            tint = if (member) palette.accent else palette.idleContent,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            AppText(
                                text = title,
                                style = AppTextStyle.Subtitle,
                                color = if (member) palette.onMember else palette.idleContent,
                            )
                            AppText(
                                text = subtitle,
                                style = AppTextStyle.Footnote,
                                color = if (member) palette.accent else palette.idleContent,
                            )
                        }
                        if (member) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.accent)
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                            ) {
                                AppText(
                                    text = badge,
                                    style = AppTextStyle.Footnote,
                                    color = palette.gradientStart,
                                )
                            }
                        }
                    }
                    if (!member) {
                        AppText(
                            text = hint,
                            style = AppTextStyle.Footnote,
                            color = palette.idleContent,
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
                    }
                }
            }
        }

        // 长按进度：一道从卡片底边长出来的金色光条。只在真的按住会员卡时可见。
        if (member && progress.value > 0f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape),
                contentAlignment = Alignment.BottomStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.value)
                        .height(4.dp)
                        .background(palette.accent),
                )
            }
        }
    }
}

/** 长按退回普通用户所需的时长。抽成常量，界面与测试用同一个值。 */
const val MEMBER_HOLD_TO_REVERT_MS = 5_000

/**
 * 会员介绍页顶部那张大卡。与 [AppMemberCard] 同一套配色，但**不可点、不带手势**——
 * 这是一张展示用的卡，可点会让人以为按下去还有别的动作。
 */
@Composable
fun AppMemberHero(
    member: Boolean,
    title: String,
    subtitle: String,
    expiry: String,
    badge: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalMemberPalette.current
    val shape = RoundedCornerShape(tokens.dialogRadius)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = if (member) palette.gradientStart else palette.idleBackground,
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (member) {
                        Brush.linearGradient(listOf(palette.gradientStart, palette.gradientEnd))
                    } else {
                        Brush.linearGradient(listOf(palette.idleBackground, palette.idleBackground))
                    },
                )
                .padding(tokens.sectionSpacing),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIconTint(
                        icon = AppIcon.Member,
                        modifier = Modifier.size(40.dp),
                        tint = if (member) palette.accent else palette.idleContent,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        AppText(
                            text = title,
                            style = AppTextStyle.Title,
                            color = if (member) palette.onMember else palette.idleContent,
                        )
                    }
                    if (member) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.accent)
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        ) {
                            AppText(
                                text = badge,
                                style = AppTextStyle.Footnote,
                                color = palette.gradientStart,
                            )
                        }
                    }
                }
                AppText(
                    text = subtitle,
                    style = AppTextStyle.Body,
                    color = if (member) palette.onMember else palette.idleContent,
                    modifier = Modifier.padding(top = tokens.itemSpacing),
                )
                AppText(
                    text = expiry,
                    style = AppTextStyle.Footnote,
                    color = if (member) palette.accent else palette.idleContent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalMemberPalette
import top.yukonga.miuix.kmp.basic.Surface

/**
 * 设置页顶部那张会员卡（娱乐功能）。
 *
 * 两个形态共用一个组件，因为它们是**同一张卡的两种状态**：普通用户是素净的蓝灰底、
 * 提示"点击查看"；会员是蓝色渐变 + 金角标，是那个"身份标识"本身。
 *
 * 轻点交给调用方决定做什么：普通用户进会员介绍页；会员则是娱乐性的"点一次减 10 年"，
 * 满 10 次取消会员身份。卡片本身不持有计数——计数要随"再次进入设置"一起清零，
 * 那属于页面级状态，放在这里会被 pager 的组合生命周期卡住。
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalMemberPalette.current
    val shape = RoundedCornerShape(tokens.cardRadius)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
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
                        Brush.linearGradient(listOf(palette.idleBackground, palette.idleBackground))
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
}

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
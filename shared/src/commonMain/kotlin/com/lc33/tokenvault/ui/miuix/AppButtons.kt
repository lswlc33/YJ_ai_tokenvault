package com.lc33.tokenvault.ui.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.platform.Haptics
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按钮与勾选框的包装（M1 锁屏用）。
 *
 * 单独一个文件而不是塞进 [AppText] 那一批里：M0.8 包的是"页面骨架要用的"，
 * 这一批是"引导与解锁要用的"，两批的改动节奏不一样，混在一个 500 行的文件里
 * 每次都要整份重读。
 *
 * 阶段3 UI 规范整改：删掉 `AppPrimaryButton` / `AppSecondaryButton` 两个长方形实心按钮
 * 封装——MIUIX 的实心 `Button` 只允许出现在弹窗/对话框里，页面主体一律用文本按钮
 * （见 AppComponents.kt 的 [AppTextButton]，`primary` 区分主动作/次动作）。
 */

/**
 * 数字键盘上的一个键。
 *
 * [minSize] 默认 64dp 而不是 `minTouchTarget` 的 48dp：解锁是每天要做几次、经常单手做、
 * 而且输错要罚等待的操作（§7.2 的退避阶梯），键太小的代价不是"不好点"而是"被罚等 30 秒"。
 */
@Composable
fun AppKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minSize: Dp = 64.dp,
) {
    KeyShell(onClick = onClick, modifier = modifier, enabled = enabled, minSize = minSize) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            style = MiuixTheme.textStyles.title3,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * 键盘上的图标键。与 [AppKeyButton] 同形，只是画图标——退格键没有合适的字符
 * （`⌫` 在部分设备的字体里是空豆腐块）。
 *
 * [contentDescription] 给 `Icon`，不用 `clearAndSetSemantics` 去盖整个键：那个修饰符
 * 挂在 `Surface` 外面时会把 `Surface` 内部 `clickable` 贡献的点击动作一起清掉，
 * 于是读屏用户听得见标签、却点不动这个键。
 */
@Composable
fun AppKeyIconButton(
    icon: AppIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minSize: Dp = 64.dp,
) {
    KeyShell(onClick = onClick, modifier = modifier, enabled = enabled, minSize = minSize) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon.imageVector(),
                contentDescription = contentDescription,
                tint = if (enabled) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
        }
    }
}

/** 两种键唯一的区别是里面画什么，圆形、配色、最小尺寸、禁用态都该只有一份。 */
@Composable
private fun KeyShell(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    minSize: Dp,
    content: @Composable () -> Unit,
) {
    Surface(
        // PIN 键是自绘键盘，不会经过 AppTextButton 那条通用反馈；这里补上轻触觉，
        // 否则用户只能靠视觉确认按键已生效。
        onClick = {
            Haptics.tap()
            onClick()
        },
        modifier = modifier.defaultMinSize(minWidth = minSize, minHeight = minSize),
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = MiuixTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

/**
 * 勾选行。整行可点，不要求用户瞄准那个小方框。
 *
 * 恢复密钥那一页用它做"我已保存"的闸（§7.1）：勾上之前「继续」不可点。
 * 用勾选框而不是开关，是因为开关表达的是"设置"，而这里表达的是"我确认做过一件事"。
 */
@Composable
fun AppCheckboxRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                state = ToggleableState(checked),
                onClick = { onCheckedChange(!checked) },
                enabled = enabled,
            )
            Text(
                text = text,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body2,
            )
        }
    }
}

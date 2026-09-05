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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
 */

/**
 * 主动作按钮。一屏里最多一个——引导的「继续」、BootCorrupt 页的「从备份恢复」。
 *
 * 用 MIUIX 的 `Button` + `buttonColorsPrimary()`，不是自己拼 `Surface`：
 * 按下态、禁用态的配色都由库给，换主题时不会漏。
 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Text(
            text = text,
            color = if (enabled) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.disabledOnPrimaryButton
            },
            style = MiuixTheme.textStyles.button,
            maxLines = 1,
        )
    }
}

/** 次要动作按钮。与 [AppPrimaryButton] 同形不同色，用在"清空重来"这类不该抢眼的出口上。 */
@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.button,
            maxLines = 1,
        )
    }
}

/**
 * 数字键盘上的一个键。
 *
 * [minSize] 默认 64dp 而不是 `minTouchTarget` 的 48dp：解锁是每天要做几次、经常单手做、
 * 而且输错要罚等待的操作（§7.2 的退避阶梯），键太小的代价不是"不好点"而是"被罚等 30 秒"。
 *
 * [contentDescription] 非空时替换掉朗读内容——退格键画的是符号，读出来得是"退格"。
 */
@Composable
fun AppKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minSize: Dp = 64.dp,
    contentDescription: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minWidth = minSize, minHeight = minSize)
            .then(
                if (contentDescription == null) {
                    Modifier
                } else {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                },
            ),
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = MiuixTheme.colorScheme.surfaceContainerHigh,
    ) {
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
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = minSize, minHeight = minSize),
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        color = MiuixTheme.colorScheme.surfaceContainerHigh,
    ) {
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

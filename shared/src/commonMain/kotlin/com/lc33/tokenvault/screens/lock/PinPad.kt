package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.pin_backspace_cd
import tokenvault.shared.generated.resources.pin_dots_cd
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppKeyButton
import com.lc33.tokenvault.ui.miuix.AppKeyIconButton
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appTrackColor
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * PIN 的位数指示。
 *
 * 只画"输了几位"，**不画输了什么**——明文 PIN 不进 UI 状态（红线 1，见 [OnboardingUiState]）。
 *
 * 朗读时把整排点读成一句"已输入 3 位，共 6 位"，而不是六个各自无意义的圆点。
 */
@Composable
fun PinDots(
    filled: Int,
    slots: Int,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    val filledColor = if (isError) LocalStatusPalette.current.error else appPrimaryColor
    val emptyColor = appTrackColor
    val readout = stringResource(Res.string.pin_dots_cd, filled, slots)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = readout },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(slots) { index ->
            Box(
                modifier = Modifier
                    .size(if (index < filled) 13.dp else 11.dp)
                    .clip(CircleShape)
                    .background(if (index < filled) filledColor else emptyColor),
            )
        }
    }
}

/**
 * 数字键盘。
 *
 * 自己画而不是用系统键盘：系统数字键盘会带联想与"最近输入"的候选条，而这里输入的是
 * 能解开整个库的凭据（§7.5 的泄漏面）。另外自画的盘能保证键足够大——解锁输错要罚等待，
 * 手滑的代价比别处高。
 *
 * [enabled] 为假时整盘不接受输入：正在退避、或者正在派生密钥。
 */
@Composable
fun PinKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("123", "456", "789").forEach { row ->
            KeyRow {
                row.forEach { digit ->
                    AppKeyButton(
                        label = digit.toString(),
                        onClick = { onDigit(digit) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                    )
                }
            }
        }
        KeyRow {
            // 左下角留空：这里放任何东西都会被误触——拇指从「7」滑下来正好落在这个位置。
            Box(modifier = Modifier.weight(1f))
            AppKeyButton(
                label = "0",
                onClick = { onDigit('0') },
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            AppKeyIconButton(
                icon = AppIcon.Backspace,
                contentDescription = stringResource(Res.string.pin_backspace_cd),
                onClick = onBackspace,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

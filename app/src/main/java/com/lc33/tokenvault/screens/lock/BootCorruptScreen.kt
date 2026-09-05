package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppPrimaryButton
import com.lc33.tokenvault.ui.miuix.AppSecondaryButton
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * boot 文件解析失败（红线 26、§7.4）。
 *
 * 这一页只有两个出口，而且**两个都很可怕**——所以它必须先把三件事说清楚，
 * 否则用户只能在两个可怕的选项之间瞎猜：
 *
 * 1. 发生了什么（[reason] 是后端给的英文诊断串，原样放在最下面供报 bug 用）。
 * 2. **什么都还没被改动**：应用不会自己重建那个文件。静默重建的后果是把用户的全部密钥
 *    变成一堆永远解不开的密文，而用户会以为"应用把我的数据删了"。
 * 3. 两个出口各自会发生什么。
 *
 * 「清空重来」带二次确认，且确认文案里逐项写清要删掉什么——这是全应用唯一一个
 * "点下去就再也回不来"的按钮。
 */
@Composable
fun BootCorruptScreen(
    reason: String,
    onRestoreFromBackup: () -> Unit,
    onWipeAndStartOver: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    var askWipe by remember { mutableStateOf(false) }

    LockPage {
        LockPageHeader(
            title = stringResource(R.string.boot_corrupt_title),
            subtitle = stringResource(R.string.boot_corrupt_body),
            icon = AppIcon.Warning,
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppText(
                text = stringResource(R.string.boot_corrupt_untouched),
                style = AppTextStyle.Secondary,
                color = LocalStatusPalette.current.warn,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            AppPrimaryButton(
                text = stringResource(R.string.boot_corrupt_restore),
                onClick = onRestoreFromBackup,
                modifier = Modifier.fillMaxWidth(),
            )
            AppSecondaryButton(
                text = stringResource(R.string.boot_corrupt_wipe),
                onClick = { askWipe = true },
                modifier = Modifier.fillMaxWidth(),
            )
            AppCard {
                AppText(
                    text = stringResource(R.string.boot_corrupt_diagnostic),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
                AppText(
                    text = reason,
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    fontFamily = tokens.monoFontFamily,
                )
            }
        }

        // 弹层必须写在 LockPage **内部**：`Overlay*` 画在最近一个 MIUIX Scaffold 的
        // popupHost 里，而锁闸这几页没有外层 Shell 的 Scaffold 兜着（业务界面那一半还没建起来）。
        // 放在外面不会报错，只是什么都不显示——真机上撞过一次。
        AppDialog(
            show = askWipe,
            onDismissRequest = { askWipe = false },
            title = stringResource(R.string.boot_corrupt_wipe_confirm_title),
            summary = stringResource(R.string.boot_corrupt_wipe_confirm_body),
        ) {
            AppSecondaryButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = { askWipe = false },
                modifier = Modifier.fillMaxWidth(),
            )
            AppPrimaryButton(
                text = stringResource(R.string.boot_corrupt_wipe_confirm),
                onClick = {
                    askWipe = false
                    onWipeAndStartOver()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

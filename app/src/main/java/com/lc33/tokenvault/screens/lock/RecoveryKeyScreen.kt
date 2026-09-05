package com.lc33.tokenvault.screens.lock

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppPrimaryButton
import com.lc33.tokenvault.ui.miuix.AppSecondaryButton
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 恢复密钥页（设置 → 安全 → 恢复密钥）。
 *
 * @param hasKey 当前有没有一份恢复密钥的包裹。没有时这一页是"补一把"，有时是"换一把"。
 * @param generated 刚刚生成、正在展示的那一把。**只有这一刻能看到它**——见下。
 */
@Immutable
data class RecoveryKeyUiState(
    val hasKey: Boolean = false,
    val generated: String? = null,
    val saved: Boolean = false,
    val busy: Boolean = false,
)

/**
 * 恢复密钥的查看与重新生成。
 *
 * **这一页看不到已有的那把恢复密钥**，而且必须把这件事说出来：库里存的是"用它派生出的密钥
 * 包裹过的 DEK"，恢复密钥自己从不落盘（红线 1）。所以"忘了抄"的唯一补救是**换一把新的**，
 * 而换新的会让旧的立刻失效（§7.1）——如果用户其实抄过旧的、只是找不到了，
 * 换新之后那张纸就成了废纸。这个代价必须在按下按钮之前讲清楚，所以有二次确认。
 */
@Composable
fun RecoveryKeyScreen(
    state: RecoveryKeyUiState,
    onRotate: () -> Unit,
    onCopy: () -> Unit,
    onSavedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    var askRotate by remember { mutableStateOf(false) }

    CredentialPage(titleRes = R.string.recovery_key_settings_title, onBack = onBack) {
        if (state.generated != null) {
            LockPageHeader(
                title = stringResource(R.string.recovery_key_title),
                subtitle = stringResource(R.string.recovery_key_subtitle),
                icon = null,
            )
            Spacer(Modifier.height(tokens.sectionSpacing))
            RecoveryKeyPanel(
                display = state.generated,
                saved = state.saved,
                onSavedChange = onSavedChange,
                onCopy = onCopy,
                enabled = !state.busy,
            )
            Spacer(Modifier.height(tokens.sectionSpacing))
            AppPrimaryButton(
                text = stringResource(R.string.recovery_key_finish),
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.saved && !state.busy,
            )
        } else {
            LockPageHeader(
                title = stringResource(R.string.recovery_key_settings_title),
                subtitle = stringResource(
                    if (state.hasKey) R.string.recovery_key_present else R.string.recovery_key_absent,
                ),
                icon = null,
            )
            Spacer(Modifier.height(tokens.sectionSpacing))
            if (state.hasKey) {
                // 只有"确实有一把"时才说得上"看不到了"。没有的时候说这句是无中生有。
                AppText(
                    text = stringResource(R.string.recovery_key_cannot_show),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                )
                Spacer(Modifier.height(tokens.itemSpacing))
            } else {
                AppText(
                    text = stringResource(R.string.recovery_key_missing_warning),
                    style = AppTextStyle.Body,
                    color = LocalStatusPalette.current.warn,
                )
                Spacer(Modifier.height(tokens.itemSpacing))
            }
            if (state.busy) {
                AppLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(tokens.itemSpacing))
            }
            AppPrimaryButton(
                text = stringResource(
                    if (state.hasKey) R.string.recovery_key_rotate else R.string.recovery_key_create,
                ),
                onClick = { if (state.hasKey) askRotate = true else onRotate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
        }

        // 弹层写在页面内部：Overlay* 画在最近一个 Scaffold 的 popupHost 里
        AppDialog(
            show = askRotate,
            onDismissRequest = { askRotate = false },
            title = stringResource(R.string.recovery_key_rotate_confirm_title),
            summary = stringResource(R.string.recovery_key_rotate_confirm_body),
        ) {
            AppSecondaryButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = { askRotate = false },
                modifier = Modifier.fillMaxWidth(),
            )
            AppPrimaryButton(
                text = stringResource(R.string.recovery_key_rotate_confirm),
                onClick = {
                    askRotate = false
                    onRotate()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

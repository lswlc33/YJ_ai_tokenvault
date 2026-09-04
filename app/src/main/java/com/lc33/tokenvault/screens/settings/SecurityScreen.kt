package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.SectionTitle

/**
 * 安全（计划.md §13.4、§7.3–7.5）。
 *
 * 三条措辞是刻意的，不要"优化"成更好听的说法：
 * - 生物识别那一行的副文案要说清它**只是解 DEK 的另一条路**，不是第二道锁。
 * - 「展示密钥时防截屏」默认开且允许关，但关掉的后果要写在副文案里。
 * - 恢复密钥那一行是"忘记 PIN"唯一的出路（§7.1），所以它不藏在折叠区里。
 */
@Composable
fun SecurityScreen(
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onRecoveryKey: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.security_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(R.string.security_section_credential)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.security_change_pin),
                summary = stringResource(R.string.security_change_pin_summary),
                onClick = onChangePin,
            )
        }
        item {
            AppArrowRow(
                title = stringResource(R.string.security_recovery_key),
                summary = stringResource(R.string.security_recovery_key_summary),
                onClick = onRecoveryKey,
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.security_biometric),
                summary = stringResource(R.string.security_biometric_summary),
                checked = draft.biometricUnlock,
                onCheckedChange = { onChange(draft.copy(biometricUnlock = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.security_section_lock)) }
        item {
            AppDropdownRow(
                title = stringResource(R.string.security_auto_lock),
                items = stringArrayResource(R.array.auto_lock_options).toList(),
                selectedIndex = draft.autoLockIndex,
                onSelect = { onChange(draft.copy(autoLockIndex = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.security_idle_lock),
                summary = stringResource(R.string.security_idle_lock_summary),
                checked = draft.idleLock,
                onCheckedChange = { onChange(draft.copy(idleLock = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.security_lock_on_screen_off),
                checked = draft.lockOnScreenOff,
                onCheckedChange = { onChange(draft.copy(lockOnScreenOff = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.security_section_leak)) }
        item {
            AppSwitchRow(
                title = stringResource(R.string.security_secure_flag),
                summary = stringResource(R.string.security_secure_flag_summary),
                checked = draft.secureFlag,
                onCheckedChange = { onChange(draft.copy(secureFlag = it)) },
            )
        }
        item {
            AppDropdownRow(
                title = stringResource(R.string.security_clipboard_clear),
                summary = stringResource(R.string.security_clipboard_clear_summary),
                items = stringArrayResource(R.array.clipboard_clear_options).toList(),
                selectedIndex = draft.clipboardClearIndex,
                onSelect = { onChange(draft.copy(clipboardClearIndex = it)) },
            )
        }
    }
}

package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auto_lock_options
import tokenvault.shared.generated.resources.clipboard_clear_options
import tokenvault.shared.generated.resources.security_auto_lock
import tokenvault.shared.generated.resources.security_change_pin
import tokenvault.shared.generated.resources.security_change_pin_summary
import tokenvault.shared.generated.resources.security_clipboard_clear
import tokenvault.shared.generated.resources.security_clipboard_clear_summary
import tokenvault.shared.generated.resources.security_idle_lock
import tokenvault.shared.generated.resources.security_idle_lock_summary
import tokenvault.shared.generated.resources.security_lock_now
import tokenvault.shared.generated.resources.security_lock_now_summary
import tokenvault.shared.generated.resources.security_lock_on_screen_off
import tokenvault.shared.generated.resources.security_section_credential
import tokenvault.shared.generated.resources.security_section_leak
import tokenvault.shared.generated.resources.security_section_lock
import tokenvault.shared.generated.resources.security_title
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.SectionTitle

/**
 * 安全（计划.md §13.4、§7.3–7.5）。
 *
 * 阶段1 迁移后删掉生物识别与恢复密钥两行，只剩改 PIN 与各类锁定/剪贴板设置。
 *
 * **自动锁定那一行、前台空闲与屏幕关闭两行、剪贴板清除都有各自的权威
 * 存储**：`app_settings.autoLockSeconds`、`app_settings.idleLockSeconds` /
 * `app_settings.lockOnScreenOff`、`app_settings.clipboardClearSeconds`（红线 31），
 * 都由 `SecurityViewModel` 从各自的权威存储派生。
 */
@Composable
fun SecurityScreen(
    autoLockIndex: Int,
    idleLock: Boolean,
    lockOnScreenOff: Boolean,
    clipboardClearIndex: Int,
    onAutoLockIndexChange: (Int) -> Unit,
    onIdleLockChange: (Boolean) -> Unit,
    onLockOnScreenOffChange: (Boolean) -> Unit,
    onClipboardClearIndexChange: (Int) -> Unit,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onLockNow: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.security_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(Res.string.security_section_credential)) }
        item {
            AppArrowRow(
                title = stringResource(Res.string.security_change_pin),
                summary = stringResource(Res.string.security_change_pin_summary),
                onClick = onChangePin,
            )
        }

        item { SectionTitle(text = stringResource(Res.string.security_section_lock)) }
        item {
            AppArrowRow(
                title = stringResource(Res.string.security_lock_now),
                summary = stringResource(Res.string.security_lock_now_summary),
                onClick = onLockNow,
            )
        }
        item {
            AppDropdownRow(
                title = stringResource(Res.string.security_auto_lock),
                items = stringArrayResource(Res.array.auto_lock_options).toList(),
                selectedIndex = autoLockIndex,
                onSelect = onAutoLockIndexChange,
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(Res.string.security_idle_lock),
                summary = stringResource(Res.string.security_idle_lock_summary),
                checked = idleLock,
                onCheckedChange = onIdleLockChange,
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(Res.string.security_lock_on_screen_off),
                checked = lockOnScreenOff,
                onCheckedChange = onLockOnScreenOffChange,
            )
        }

        item { SectionTitle(text = stringResource(Res.string.security_section_leak)) }
        item {
            AppDropdownRow(
                title = stringResource(Res.string.security_clipboard_clear),
                summary = stringResource(Res.string.security_clipboard_clear_summary),
                items = stringArrayResource(Res.array.clipboard_clear_options).toList(),
                selectedIndex = clipboardClearIndex,
                onSelect = onClipboardClearIndexChange,
            )
        }
    }
}

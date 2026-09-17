package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auto_lock_options
import tokenvault.shared.generated.resources.clipboard_clear_options
import tokenvault.shared.generated.resources.security_auto_lock
import tokenvault.shared.generated.resources.security_biometric
import tokenvault.shared.generated.resources.security_biometric_summary
import tokenvault.shared.generated.resources.security_biometric_unavailable
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
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.SectionTitle

/**
 * 安全（计划.md §13.4、§7.3–7.5）。
 *
 * 凭据一组里有两条路：改 PIN，以及可选的生物识别解锁。生物识别不是第二道锁，
 * 而是通向**同一个数据密钥**的另一条路，所以它和 PIN 放在同一组里、不是单独的安全区。
 *
 * **自动锁定那一行、前台空闲与屏幕关闭两行、剪贴板清除都有各自的权威
 * 存储**：`app_settings.autoLockSeconds`、`app_settings.idleLockSeconds` /
 * `app_settings.lockOnScreenOff`、`app_settings.clipboardClearSeconds`（红线 31），
 * 都由 `SecurityViewModel` 从各自的权威存储派生。生物识别开关的权威存储是 **boot**
 * （解锁前就要读），同样由 ViewModel 派生。
 */
@Composable
fun SecurityScreen(
    autoLockIndex: Int,
    idleLock: Boolean,
    lockOnScreenOff: Boolean,
    clipboardClearIndex: Int,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    biometricBusy: Boolean,
    onAutoLockIndexChange: (Int) -> Unit,
    onIdleLockChange: (Boolean) -> Unit,
    onLockOnScreenOffChange: (Boolean) -> Unit,
    onClipboardClearIndexChange: (Int) -> Unit,
    onBiometricChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onLockNow: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.security_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(Res.string.security_section_credential)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.security_change_pin),
                    summary = stringResource(Res.string.security_change_pin_summary),
                    onClick = onChangePin,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.security_biometric),
                    summary = stringResource(
                        if (biometricAvailable) {
                            Res.string.security_biometric_summary
                        } else {
                            Res.string.security_biometric_unavailable
                        },
                    ),
                    checked = biometricEnabled,
                    // 设备不支持 / 没录生物识别时开关置灰：按下去系统弹不出验证框，
                    // 不置灰的话用户会以为点了没反应。
                    enabled = biometricAvailable && !biometricBusy,
                    onCheckedChange = onBiometricChange,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.security_section_lock)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.security_lock_now),
                    summary = stringResource(Res.string.security_lock_now_summary),
                    onClick = onLockNow,
                )
                AppDropdownRow(
                    title = stringResource(Res.string.security_auto_lock),
                    items = stringArrayResource(Res.array.auto_lock_options).toList(),
                    selectedIndex = autoLockIndex,
                    onSelect = onAutoLockIndexChange,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.security_idle_lock),
                    summary = stringResource(Res.string.security_idle_lock_summary),
                    checked = idleLock,
                    onCheckedChange = onIdleLockChange,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.security_lock_on_screen_off),
                    checked = lockOnScreenOff,
                    onCheckedChange = onLockOnScreenOffChange,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.security_section_leak)) }
        item {
            AppPreferenceGroup {
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
}

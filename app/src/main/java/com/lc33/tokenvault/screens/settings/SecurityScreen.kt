package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.domain.BiometricAvailability
import com.lc33.tokenvault.screens.lock.biometricUnavailableRes
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.SectionTitle

/**
 * 生物识别那一行需要知道的两件事。
 *
 * 分成两个字段而不是一个三态枚举：**"开着"与"系统能不能用"是两回事**（红线 5）。
 * 用户关掉开关之后，系统仍然报 `AVAILABLE`，而这一行必须画成"关"；反过来，开关开着
 * 但指纹被清空时 `BiometricUnlocker` 会把开关一起关掉，所以这一行不需要"开着但用不了"这一档。
 */
data class BiometricRowState(
    val enabled: Boolean = false,
    val availability: BiometricAvailability = BiometricAvailability.NO_HARDWARE,
)

/**
 * 安全（计划.md §13.4、§7.3–7.5）。
 *
 * 三条措辞是刻意的，不要"优化"成更好听的说法：
 * - 生物识别那一行的副文案要说清它**只是解 DEK 的另一条路**，不是第二道锁。
 * - 「展示密钥时防截屏」默认开且允许关，但关掉的后果要写在副文案里。
 * - 恢复密钥那一行是"忘记 PIN"唯一的出路（§7.1），所以它不藏在折叠区里。
 *
 * **生物识别那一行、自动锁定那一行、前台空闲与屏幕关闭两行都不吃 [SettingsDraft]**：
 * 前两者的权威存储分别是 `boot.biometricEnabled`（红线 5）与 `app_settings.autoLockSeconds`
 * （红线 31），后两者是 `app_settings.idleLockSeconds` / `app_settings.lockOnScreenOff`，
 * 四者都由 `SecurityViewModel` 从各自的权威存储派生。剩下几项还在 [SettingsDraft] 上，
 * 那是它们各自的消费方写完之后的活，现在是**切页保留、杀进程丢弃**。
 */
@Composable
fun SecurityScreen(
    draft: SettingsDraft,
    biometric: BiometricRowState,
    autoLockIndex: Int,
    idleLock: Boolean,
    lockOnScreenOff: Boolean,
    onChange: (SettingsDraft) -> Unit,
    onBiometricChange: (Boolean) -> Unit,
    onAutoLockIndexChange: (Int) -> Unit,
    onIdleLockChange: (Boolean) -> Unit,
    onLockOnScreenOffChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onRecoveryKey: () -> Unit,
    onLockNow: () -> Unit,
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
            // 不可用时把原因写在副文案里、并且禁用这一行：只画成"关"而不说为什么，
            // 用户会反复去点它（七档里有三档是他自己能解决的，见 biometricUnavailableRes）。
            val unavailable = biometricUnavailableRes(biometric.availability)
            AppSwitchRow(
                title = stringResource(R.string.security_biometric),
                summary = stringResource(unavailable ?: R.string.security_biometric_summary),
                checked = biometric.enabled,
                onCheckedChange = onBiometricChange,
                enabled = biometric.enabled || unavailable == null,
            )
        }

        item { SectionTitle(text = stringResource(R.string.security_section_lock)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.security_lock_now),
                summary = stringResource(R.string.security_lock_now_summary),
                onClick = onLockNow,
            )
        }
        item {
            AppDropdownRow(
                title = stringResource(R.string.security_auto_lock),
                items = stringArrayResource(R.array.auto_lock_options).toList(),
                selectedIndex = autoLockIndex,
                onSelect = onAutoLockIndexChange,
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.security_idle_lock),
                summary = stringResource(R.string.security_idle_lock_summary),
                checked = idleLock,
                onCheckedChange = onIdleLockChange,
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.security_lock_on_screen_off),
                checked = lockOnScreenOff,
                onCheckedChange = onLockOnScreenOffChange,
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

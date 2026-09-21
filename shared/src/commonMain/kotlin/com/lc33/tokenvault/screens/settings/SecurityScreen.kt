package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.auto_lock_options
import tokenvault.shared.generated.resources.security_auto_lock
import tokenvault.shared.generated.resources.security_biometric
import tokenvault.shared.generated.resources.security_biometric_disable_failed
import tokenvault.shared.generated.resources.security_biometric_disable_failed_detail
import tokenvault.shared.generated.resources.security_biometric_failed_detail
import tokenvault.shared.generated.resources.security_biometric_locked_out
import tokenvault.shared.generated.resources.security_biometric_summary
import tokenvault.shared.generated.resources.security_biometric_unavailable
import tokenvault.shared.generated.resources.security_boot_write_failed
import tokenvault.shared.generated.resources.security_change_pin
import tokenvault.shared.generated.resources.security_change_pin_summary
import tokenvault.shared.generated.resources.security_idle_lock
import tokenvault.shared.generated.resources.security_idle_lock_summary
import tokenvault.shared.generated.resources.security_lock_now
import tokenvault.shared.generated.resources.security_lock_now_summary
import tokenvault.shared.generated.resources.security_lock_on_screen_off
import tokenvault.shared.generated.resources.security_section_credential
import tokenvault.shared.generated.resources.security_section_lock
import tokenvault.shared.generated.resources.security_title
import com.lc33.tokenvault.platform.supportsIdleLock
import com.lc33.tokenvault.platform.supportsLockOnScreenOff
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 安全（计划.md §13.4、§7.3–7.5）。
 *
 * 凭据一组里有两条路：改 PIN，以及可选的生物识别解锁。生物识别不是第二道锁，
 * 而是通向**同一个数据密钥**的另一条路，所以它和 PIN 放在同一组里、不是单独的安全区。
 *
 * **自动锁定那一行、前台空闲与屏幕关闭两行各自有权威存储**：`app_settings.autoLockSeconds`、
 * `app_settings.idleLockSeconds` / `app_settings.lockOnScreenOff`（红线 31），
 * 都由 `SecurityViewModel` 从各自的权威存储派生。生物识别开关的权威存储是 **boot**
 * （解锁前就要读），同样由 ViewModel 派生。
 *
 * 凭据那一组底部的说明行是"这件事没成"：生物识别启用失败、以及 boot 写不进去（并发写
 * 冲突）都发生在这一页，而这一页就是用户唯一能看到后果的地方。失败原因里带上系统给的
 * 那一句（`NSError` / `BiometricPrompt` 的文案本身就跟着系统语言走），比我们编一句
 * "出错了"更准。
 */
@Composable
fun SecurityScreen(
    autoLockIndex: Int,
    idleLock: Boolean,
    lockOnScreenOff: Boolean,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    biometricBusy: Boolean,
    onAutoLockIndexChange: (Int) -> Unit,
    onIdleLockChange: (Boolean) -> Unit,
    onLockOnScreenOffChange: (Boolean) -> Unit,
    onBiometricChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    onLockNow: () -> Unit,
    /** 生物识别启用失败的具体原因（系统给的那一句）。null = 没有失败。 */
    biometricError: String? = null,
    /** 生物识别被系统暂时锁住（连续失败太多次）。这一档不说"失败"，说"等一会儿"。 */
    biometricLockedOut: Boolean = false,
    /** 关闭时平台上那份凭据没删掉，且系统没给原文。这一档的文案来自资源，不来自异常。 */
    biometricDisableFailed: Boolean = false,
    /** 上一次 boot 写入被拒（并发修改 / 文件损坏）。true 时画出那一行说明。 */
    bootWriteFailed: Boolean = false,
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
                // 失败就说失败，并且说清为什么：这一项的开关权威在 boot，
                // 静默弹回原位等于让用户对着一个看起来像界面抖动的东西反复点。
                val notice = when {
                    biometricLockedOut -> stringResource(Res.string.security_biometric_locked_out)
                    // 关这一侧的失败不能念"开不了"那句：说的是相反方向的半件事，
                    // 而用户接下来要处理的是"设备上可能还留着一份凭据"。
                    biometricDisableFailed && biometricError != null ->
                        stringResource(Res.string.security_biometric_disable_failed_detail, biometricError)

                    biometricDisableFailed -> stringResource(Res.string.security_biometric_disable_failed)
                    biometricError != null ->
                        stringResource(Res.string.security_biometric_failed_detail, biometricError)

                    bootWriteFailed -> stringResource(Res.string.security_boot_write_failed)
                    else -> null
                }
                if (notice != null) {
                    AppText(
                        text = notice,
                        style = AppTextStyle.Footnote,
                        color = LocalStatusPalette.current.error,
                    )
                }
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
                // 这两行是**平台能力**，不是通用能力：iOS 上既没有"用户摸了一下屏幕"的公开钩子
                // 也没有屏幕关闭广播，画出来就是一个按了确实生效、但实际上什么都不做的开关
                // （用户会以为手机在兜里 30 秒就锁了，而 DEK 一直留在内存里）。
                // 所以按能力位整行不画，而不是置灰——`AutoLocker` 那边还各自挡了一道。
                if (supportsIdleLock) {
                    AppSwitchRow(
                        title = stringResource(Res.string.security_idle_lock),
                        summary = stringResource(Res.string.security_idle_lock_summary),
                        checked = idleLock,
                        onCheckedChange = onIdleLockChange,
                    )
                }
                if (supportsLockOnScreenOff) {
                    AppSwitchRow(
                        title = stringResource(Res.string.security_lock_on_screen_off),
                        checked = lockOnScreenOff,
                        onCheckedChange = onLockOnScreenOffChange,
                    )
                }
            }
        }
    }
}

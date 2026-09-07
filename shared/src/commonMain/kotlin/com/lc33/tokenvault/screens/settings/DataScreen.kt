package com.lc33.tokenvault.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.data_clear_log
import tokenvault.shared.generated.resources.data_clear_log_confirm_body
import tokenvault.shared.generated.resources.data_clear_log_confirm_title
import tokenvault.shared.generated.resources.data_clear_log_summary
import tokenvault.shared.generated.resources.data_clear_probe
import tokenvault.shared.generated.resources.data_clear_probe_confirm_body
import tokenvault.shared.generated.resources.data_clear_probe_confirm_title
import tokenvault.shared.generated.resources.data_clear_probe_summary
import tokenvault.shared.generated.resources.data_groups_summary
import tokenvault.shared.generated.resources.data_log
import tokenvault.shared.generated.resources.data_log_summary
import tokenvault.shared.generated.resources.data_section_danger
import tokenvault.shared.generated.resources.data_section_log
import tokenvault.shared.generated.resources.data_section_organize
import tokenvault.shared.generated.resources.data_title
import tokenvault.shared.generated.resources.groups_title
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.SectionTitle

/**
 * 数据（计划.md §13.4）。
 *
 * 两个"清空"都走二次确认，而且确认文案里要写清连带删掉什么——这一页是全应用
 * 最容易误触出不可挽回后果的地方。确认对话框在这里弹（页面层持有 `show` 状态），
 * 真动手交给调用方传进来的 `onClearProbeResults` / `onClearLog`。
 */
@Composable
fun DataScreen(
    onBack: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenLog: () -> Unit,
    onClearProbeResults: () -> Unit,
    onClearLog: () -> Unit,
) {
    // 两个确认对话框的开关。`true` 表示「弹着等待确认」，确认或取消都立刻关掉。
    var confirmProbe by remember { mutableStateOf(false) }
    var confirmLog by remember { mutableStateOf(false) }

    SettingsSubPage(titleRes = Res.string.data_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(Res.string.data_section_organize)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.groups_title),
                    summary = stringResource(Res.string.data_groups_summary),
                    onClick = onOpenGroups,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.data_section_log)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.data_log),
                    summary = stringResource(Res.string.data_log_summary),
                    onClick = onOpenLog,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.data_section_danger)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.data_clear_probe),
                    summary = stringResource(Res.string.data_clear_probe_summary),
                    onClick = { confirmProbe = true },
                )
                AppArrowRow(
                    title = stringResource(Res.string.data_clear_log),
                    summary = stringResource(Res.string.data_clear_log_summary),
                    onClick = { confirmLog = true },
                )
            }
        }
    }

    AppDialog(
        show = confirmProbe,
        onDismissRequest = { confirmProbe = false },
        title = stringResource(Res.string.data_clear_probe_confirm_title),
        summary = stringResource(Res.string.data_clear_probe_confirm_body),
        confirmText = stringResource(Res.string.data_clear_probe),
        onConfirm = {
            confirmProbe = false
            onClearProbeResults()
        },
    )

    AppDialog(
        show = confirmLog,
        onDismissRequest = { confirmLog = false },
        title = stringResource(Res.string.data_clear_log_confirm_title),
        summary = stringResource(Res.string.data_clear_log_confirm_body),
        confirmText = stringResource(Res.string.data_clear_log),
        onConfirm = {
            confirmLog = false
            onClearLog()
        },
    )
}

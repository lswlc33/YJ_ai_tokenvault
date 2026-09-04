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
 * 探测（计划.md §13.4、§8.1、§8.3）。
 *
 * 两处文案是 M0.5 实测之后改的，别退回去：
 * - 「允许升级版 L2 消耗额度」的副文案不能只说"约 16 token"。实测里我们只发一个
 *   `ping`，中转站回的 `input_tokens` 是 7021——输入 token 数由对方决定，我们既
 *   看不到也控制不了（§8.3）。
 * - host 最小间隔不是可选优化：挂 Cloudflare 的站同 host 2.4 秒内第 3 个请求就撞
 *   `error code: 1015`（红线 29）。所以它有入口但下限不给到 0。
 */
@Composable
fun ProbeSettingsScreen(
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onBack: () -> Unit,
    onEditThresholds: () -> Unit,
    onEditKeywords: () -> Unit,
    onEditProxy: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.probe_settings_title, onBack = onBack) {
        item { SectionTitle(text = stringResource(R.string.probe_section_when)) }
        item {
            AppDropdownRow(
                title = stringResource(R.string.probe_auto_when),
                items = stringArrayResource(R.array.auto_probe_options).toList(),
                selectedIndex = draft.autoProbeIndex,
                onSelect = { onChange(draft.copy(autoProbeIndex = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.probe_section_cost)) }
        item {
            AppSwitchRow(
                title = stringResource(R.string.probe_enable_l3),
                summary = stringResource(R.string.probe_enable_l3_summary),
                checked = draft.enableL3,
                onCheckedChange = { onChange(draft.copy(enableL3 = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.probe_allow_upgraded_l2),
                summary = stringResource(R.string.probe_allow_upgraded_l2_summary),
                checked = draft.allowUpgradedL2,
                onCheckedChange = { onChange(draft.copy(allowUpgradedL2 = it)) },
            )
        }
        item {
            AppArrowRow(
                title = stringResource(R.string.probe_thresholds),
                summary = stringResource(R.string.probe_thresholds_summary),
                onClick = onEditThresholds,
            )
        }

        item { SectionTitle(text = stringResource(R.string.probe_section_rate)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.probe_pacing),
                summary = stringResource(R.string.probe_pacing_summary),
                onClick = onEditThresholds,
            )
        }

        item { SectionTitle(text = stringResource(R.string.probe_section_client)) }
        item {
            AppSwitchRow(
                title = stringResource(R.string.probe_sniff),
                summary = stringResource(R.string.probe_sniff_summary),
                checked = draft.sniffClientProfile,
                onCheckedChange = { onChange(draft.copy(sniffClientProfile = it)) },
            )
        }
        item {
            AppArrowRow(
                title = stringResource(R.string.probe_keywords),
                summary = stringResource(R.string.probe_keywords_summary),
                onClick = onEditKeywords,
            )
        }

        item { SectionTitle(text = stringResource(R.string.probe_section_network)) }
        item {
            AppSwitchRow(
                title = stringResource(R.string.probe_verbose_log),
                summary = stringResource(R.string.probe_verbose_log_summary),
                checked = draft.verboseHttpLog,
                onCheckedChange = { onChange(draft.copy(verboseHttpLog = it)) },
            )
        }
        item {
            AppArrowRow(
                title = stringResource(R.string.probe_proxy),
                summary = stringResource(R.string.probe_proxy_summary),
                onClick = onEditProxy,
            )
        }
    }
}

package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 探测（计划.md §13.4、§8.1、§8.3）。
 *
 * **这一页的级别开关是"新建供应商时的默认值"，不是总开关**（红线 36）：每家站的规则
 * 都不一样——有的按 ToS 不允许自动化探测，有的三个请求就限流——所以权威在每个供应商
 * 自己身上。这件事必须用一整句话在页面最上面说清，不能塞在某个开关的副文案里。
 *
 * 另外两处文案是 M0.5 实测之后改的，别退回去：
 * - host 最小间隔不是可选优化：挂 Cloudflare 的站同 host 2.4 秒内第 3 个请求就撞
 *   `error code: 1015`（红线 29）。
 * - 原来这里有个「升级版 L2 是否允许消耗额度」开关，已经删掉：红线 36 之后自动路径
 *   一分不花，不需要一个默认打开、藏在设置里、后果是花钱的开关。
 */
@Composable
fun ProbeSettingsScreen(
    draft: SettingsDraft,
    sniffClientProfile: Boolean,
    onChange: (SettingsDraft) -> Unit,
    onSniffClientProfileChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenManage: () -> Unit,
    onEditThresholds: () -> Unit,
    onEditKeywords: () -> Unit,
    onEditProxy: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.probe_settings_title, onBack = onBack) {
        // 这一页最容易被误解的地方：级别开关不是总开关。所以说明放在最上面，
        // 而不是塞在某个开关的副文案里。
        item { DefaultsNoticeCard(onOpenManage) }

        item { SectionTitle(text = stringResource(R.string.probe_section_defaults)) }
        item {
            AppSwitchRow(
                title = stringResource(R.string.editor_probe_reachability),
                checked = draft.defaultProbeReachability,
                onCheckedChange = { onChange(draft.copy(defaultProbeReachability = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.editor_probe_keys),
                checked = draft.defaultProbeKeys,
                onCheckedChange = { onChange(draft.copy(defaultProbeKeys = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.editor_probe_balance),
                checked = draft.defaultProbeBalance,
                onCheckedChange = { onChange(draft.copy(defaultProbeBalance = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.editor_probe_models),
                summary = stringResource(R.string.editor_probe_models_summary),
                checked = draft.defaultProbeModels,
                onCheckedChange = { onChange(draft.copy(defaultProbeModels = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.probe_section_when)) }
        item {
            AppDropdownRow(
                title = stringResource(R.string.probe_auto_when),
                summary = stringResource(R.string.probe_auto_when_summary),
                items = stringArrayResource(R.array.auto_probe_options).toList(),
                selectedIndex = draft.autoProbeIndex,
                onSelect = { onChange(draft.copy(autoProbeIndex = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.probe_section_cost)) }
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
                checked = sniffClientProfile,
                onCheckedChange = onSniffClientProfileChange,
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

@Composable
private fun DefaultsNoticeCard(onOpenManage: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(
            text = stringResource(R.string.probe_defaults_notice),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
        AppTextButton(
            text = stringResource(R.string.probe_defaults_goto_manage),
            onClick = onOpenManage,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
    }
}

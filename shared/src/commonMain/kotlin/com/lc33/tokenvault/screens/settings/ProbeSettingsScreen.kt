package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.editor_probe_balance
import tokenvault.shared.generated.resources.editor_probe_balance_default_summary
import tokenvault.shared.generated.resources.editor_probe_keys
import tokenvault.shared.generated.resources.editor_probe_models
import tokenvault.shared.generated.resources.editor_probe_model_reachability
import tokenvault.shared.generated.resources.editor_probe_model_reachability_summary
import tokenvault.shared.generated.resources.editor_probe_models_summary
import tokenvault.shared.generated.resources.editor_probe_reachability
import tokenvault.shared.generated.resources.probe_auto_refresh
import tokenvault.shared.generated.resources.probe_auto_refresh_interval
import tokenvault.shared.generated.resources.probe_auto_refresh_interval_summary
import tokenvault.shared.generated.resources.probe_auto_refresh_interval_options
import tokenvault.shared.generated.resources.probe_auto_refresh_summary
import tokenvault.shared.generated.resources.probe_defaults_goto_manage
import tokenvault.shared.generated.resources.probe_defaults_notice
import tokenvault.shared.generated.resources.probe_keywords
import tokenvault.shared.generated.resources.probe_keywords_summary
import tokenvault.shared.generated.resources.probe_max_concurrency
import tokenvault.shared.generated.resources.probe_max_concurrency_options
import tokenvault.shared.generated.resources.probe_max_concurrency_summary
import tokenvault.shared.generated.resources.probe_section_concurrency
import tokenvault.shared.generated.resources.probe_section_auto_refresh
import tokenvault.shared.generated.resources.probe_section_client
import tokenvault.shared.generated.resources.probe_section_cost
import tokenvault.shared.generated.resources.probe_section_defaults
import tokenvault.shared.generated.resources.probe_settings_title
import tokenvault.shared.generated.resources.probe_sniff
import tokenvault.shared.generated.resources.probe_sniff_summary
import tokenvault.shared.generated.resources.probe_thresholds
import tokenvault.shared.generated.resources.probe_thresholds_summary
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
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
 * 唯一的例外是「自动刷新」那一节：它**是**总开关，而且管的是"什么时候刷"不是"刷什么"——
 * 发出去的仍然是各把 Key 自己那套零成本请求。默认开、间隔默认半小时（2026-09 决策：
 * 这一项要的效果就是"打开应用看到的就是新数据"，默认关等于大多数人不会去开它）。
 *
 * 另外两处文案是 M0.5 实测之后改的，别退回去：
 * - host 最小间隔不是可选优化：挂 Cloudflare 的站同 host 2.4 秒内第 3 个请求就撞
 *   `error code: 1015`（红线 29）。
 * - 原来这里有个「升级版 L2 是否允许消耗额度」开关，已经删掉：红线 36 之后自动路径
 *   一分不花，不需要一个默认打开、藏在设置里、后果是花钱的开关。
 */
@Composable
fun ProbeSettingsScreen(
    sniffClientProfile: Boolean,
    autoRefresh: Boolean,
    autoRefreshIntervalIndex: Int,
    maxConcurrencyIndex: Int,
    defaultProbeReachability: Boolean,
    defaultProbeKeys: Boolean,
    defaultProbeBalance: Boolean,
    defaultProbeModels: Boolean,
    defaultProbeModelReachability: Boolean,
    onSniffClientProfileChange: (Boolean) -> Unit,
    onAutoRefreshChange: (Boolean) -> Unit,
    onAutoRefreshIntervalIndexChange: (Int) -> Unit,
    onMaxConcurrencyIndexChange: (Int) -> Unit,
    onDefaultProbeReachabilityChange: (Boolean) -> Unit,
    onDefaultProbeKeysChange: (Boolean) -> Unit,
    onDefaultProbeBalanceChange: (Boolean) -> Unit,
    onDefaultProbeModelsChange: (Boolean) -> Unit,
    onDefaultProbeModelReachabilityChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenManage: () -> Unit,
    onEditThresholds: () -> Unit,
    onEditKeywords: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.probe_settings_title, onBack = onBack) {
        // 这一页最容易被误解的地方：级别开关不是总开关。所以说明放在最上面，
        // 而不是塞在某个开关的副文案里。
        item { DefaultsNoticeCard(onOpenManage) }

        item { SectionTitle(text = stringResource(Res.string.probe_section_defaults)) }
        item {
            AppPreferenceGroup {
                AppSwitchRow(
                    title = stringResource(Res.string.editor_probe_reachability),
                    checked = defaultProbeReachability,
                    onCheckedChange = onDefaultProbeReachabilityChange,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.editor_probe_keys),
                    checked = defaultProbeKeys,
                    onCheckedChange = onDefaultProbeKeysChange,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.editor_probe_balance),
                    summary = stringResource(Res.string.editor_probe_balance_default_summary),
                    checked = defaultProbeBalance,
                    onCheckedChange = onDefaultProbeBalanceChange,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.editor_probe_models),
                    summary = stringResource(Res.string.editor_probe_models_summary),
                    checked = defaultProbeModels,
                    onCheckedChange = onDefaultProbeModelsChange,
                )
                AppSwitchRow(
                    title = stringResource(Res.string.editor_probe_model_reachability),
                    summary = stringResource(Res.string.editor_probe_model_reachability_summary),
                    checked = defaultProbeModelReachability,
                    onCheckedChange = onDefaultProbeModelReachabilityChange,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.probe_section_auto_refresh)) }
        item {
            AppPreferenceGroup {
                AppSwitchRow(
                    title = stringResource(Res.string.probe_auto_refresh),
                    summary = stringResource(Res.string.probe_auto_refresh_summary),
                    checked = autoRefresh,
                    onCheckedChange = onAutoRefreshChange,
                )
                // 间隔只在开关打开时画：关着摆一枚点不动的下拉，用户要先猜"为什么不能点"，
                // 而在没有定时器的那段状态里这一档本来就没有含义。
                if (autoRefresh) {
                    AppDropdownRow(
                        title = stringResource(Res.string.probe_auto_refresh_interval),
                        summary = stringResource(Res.string.probe_auto_refresh_interval_summary),
                        items = stringArrayResource(Res.array.probe_auto_refresh_interval_options).toList(),
                        selectedIndex = autoRefreshIntervalIndex,
                        onSelect = onAutoRefreshIntervalIndexChange,
                    )
                }
            }
        }

        // 并发这一档与上面那节的性质不同：「自动刷新」管的是"什么时候刷"，而它管的是
        // **整个应用同时发几个请求**（模型列表、余额、检查更新都算），手动探测也受它管。
        // 所以单独成节，不混进"自动刷新"里，免得读成一个只管定时器的开关。
        item { SectionTitle(text = stringResource(Res.string.probe_section_concurrency)) }
        item {
            AppPreferenceGroup {
                AppDropdownRow(
                    title = stringResource(Res.string.probe_max_concurrency),
                    summary = stringResource(Res.string.probe_max_concurrency_summary),
                    items = stringArrayResource(Res.array.probe_max_concurrency_options).toList(),
                    selectedIndex = maxConcurrencyIndex,
                    onSelect = onMaxConcurrencyIndexChange,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.probe_section_cost)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.probe_thresholds),
                    summary = stringResource(Res.string.probe_thresholds_summary),
                    onClick = onEditThresholds,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.probe_section_client)) }
        item {
            AppPreferenceGroup {
                AppSwitchRow(
                    title = stringResource(Res.string.probe_sniff),
                    summary = stringResource(Res.string.probe_sniff_summary),
                    checked = sniffClientProfile,
                    onCheckedChange = onSniffClientProfileChange,
                )
                AppArrowRow(
                    title = stringResource(Res.string.probe_keywords),
                    summary = stringResource(Res.string.probe_keywords_summary),
                    onClick = onEditKeywords,
                )
            }
        }
    }
}

@Composable
private fun DefaultsNoticeCard(onOpenManage: () -> Unit) {
    val tokens = LocalAppTokens.current
    // 说明与入口分开：说明是一段提示，入口是一条动作行。
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(
            text = stringResource(Res.string.probe_defaults_notice),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
        )
    }
    AppPreferenceGroup(
        modifier = Modifier.padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        inset = false,
    ) {
        AppActionRow(
            text = stringResource(Res.string.probe_defaults_goto_manage),
            onClick = onOpenManage,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.catalog_auto_update
import tokenvault.shared.generated.resources.catalog_auto_update_summary
import tokenvault.shared.generated.resources.catalog_last_never
import tokenvault.shared.generated.resources.catalog_last_sync
import tokenvault.shared.generated.resources.catalog_update_now
import tokenvault.shared.generated.resources.catalog_update_now_summary
import tokenvault.shared.generated.resources.catalog_updating
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
import tokenvault.shared.generated.resources.data_section_catalog
import tokenvault.shared.generated.resources.data_section_danger
import tokenvault.shared.generated.resources.data_section_log
import tokenvault.shared.generated.resources.data_section_organize
import tokenvault.shared.generated.resources.data_title
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.groups_title
import tokenvault.shared.generated.resources.keymodels_catalog_failed
import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppLinearProgress
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppValueRow
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 数据（计划.md §13.4）。
 *
 * 两个"清空"都走二次确认，而且确认文案里要写清连带删掉什么——这一页是全应用
 * 最容易误触出不可挽回后果的地方。确认对话框在这里弹（页面层持有 `show` 状态），
 * 真动手交给调用方传进来的 `onClearProbeResults` / `onClearLog`。
 *
 * 「模型目录」那一节排在日志与危险操作之间：它是"库里的东西怎么维护"的第三件事，
 * 而危险操作必须留在最后一行——那一节是全页唯一不可撤销的地方，摆在中段会让人顺手点下去。
 */
@Composable
fun DataScreen(
    onBack: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenLog: () -> Unit,
    onClearProbeResults: () -> Unit,
    onClearLog: () -> Unit,
    catalogSync: CatalogSyncState,
    catalogLastSyncAt: Long,
    catalogAutoUpdate: Boolean,
    onUpdateCatalog: () -> Unit,
    onCatalogAutoUpdateChange: (Boolean) -> Unit,
) {
    val tokens = LocalAppTokens.current
    // 两个确认对话框的开关。`true` 表示「弹着等待确认」，确认或取消都立刻关掉。
    var confirmProbe by remember { mutableStateOf(false) }
    var confirmLog by remember { mutableStateOf(false) }
    // 页面级时间快照（与日志页同款）：重组时不重复读时钟，相对时间用同一基准。
    val now = remember { nowMillis() }

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

        item { SectionTitle(text = stringResource(Res.string.data_section_catalog)) }
        item {
            val running = catalogSync is CatalogSyncState.Downloading ||
                catalogSync is CatalogSyncState.Importing
            AppPreferenceGroup {
                // 「上次更新」是只读的一行：时间必须能看到，否则"关掉自动更新"之后
                // 用户不知道自己那份目录停在什么时候。0 = 从来没成功同步过。
                AppValueRow(
                    title = stringResource(Res.string.catalog_last_sync),
                    value = if (catalogLastSyncAt == 0L) {
                        stringResource(Res.string.catalog_last_never)
                    } else {
                        relativeLabel(now, catalogLastSyncAt)
                    },
                )
                AppArrowRow(
                    title = stringResource(Res.string.catalog_update_now),
                    // 副文案兼作状态位：正在跑、跑失败、平时各一句。失败那句与模型页
                    // 那条横幅同一份文案（同一种状态全应用一套说法）。
                    summary = when {
                        running -> stringResource(Res.string.catalog_updating)
                        catalogSync is CatalogSyncState.Failed ->
                            stringResource(Res.string.keymodels_catalog_failed)
                        else -> stringResource(Res.string.catalog_update_now_summary)
                    },
                    // 正在跑时不给按：第二发会被引擎的 tryLock 挡掉、界面白闪一下没反应。
                    enabled = !running,
                    onClick = onUpdateCatalog,
                )
                // 进度条只在真在跑时出现：4.7 MB 在蜂窝网上要等好几秒，只写一句"正在更新"
                // 看不出它是卡住了还是在走。下载那一段上游不报长度，所以是条不确定进度条。
                if (running) {
                    AppLinearProgress(
                        progress = (catalogSync as? CatalogSyncState.Importing)
                            ?.let { if (it.total > 0) it.rows.toFloat() / it.total else null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = tokens.screenPadding,
                                vertical = tokens.itemSpacing,
                            ),
                    )
                }
                AppSwitchRow(
                    title = stringResource(Res.string.catalog_auto_update),
                    summary = stringResource(Res.string.catalog_auto_update_summary),
                    checked = catalogAutoUpdate,
                    onCheckedChange = onCatalogAutoUpdateChange,
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
        // 清掉的是历史探测数据、且不可撤销：退路必须是看得见的按钮（AppDialog 契约）。
        dismissText = stringResource(Res.string.dialog_cancel),
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
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            confirmLog = false
            onClearLog()
        },
    )
}

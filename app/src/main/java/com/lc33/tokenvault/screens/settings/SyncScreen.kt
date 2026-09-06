package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 同步 —— 备份 / 恢复 / WebDAV / 自动备份（计划.md §13.4、§12）。
 *
 * 仪表盘那张备份卡点进来就是这一页。
 *
 * 两条不能软化的措辞：
 * - 备份口令默认沿用 PIN，所以传到云上的包同样是**分钟级可破**（§7.6）。提示常驻，
 *   不折叠。
 * - WebDAV 的 `http://` 直接拦，不给"我知道风险"的快捷勾选：Basic 凭据加整个备份包
 *   明文出去，代价太大（§7.5 同一道闸）。
 */
@Composable
fun SyncScreen(
    draft: SettingsDraft,
    backup: BackupStatus,
    onChange: (SettingsDraft) -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onWebDav: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.sync_title, onBack = onBack) {
        item { StatusCard(backup, onExport) }

        item { SectionTitle(text = stringResource(R.string.sync_section_local)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.sync_export),
                summary = stringResource(R.string.sync_export_summary),
                onClick = onExport,
            )
        }
        item {
            AppArrowRow(
                title = stringResource(R.string.sync_import),
                summary = stringResource(R.string.sync_import_summary),
                onClick = onImport,
            )
        }
        item {
            // 备份口令默认沿用 PIN，导出/恢复时每次输入（可换任意长口令）。这里没有
            // 独立的设置页——"单独设长口令"就是导出时输一个不同于 PIN 的口令，
            // 持久化独立口令属于 WebDAV 无人值守备份（可砍），所以只留一行常驻提示
            // （§12.1），不做成一个点了没反应的箭头行。
            AppText(
                text = stringResource(R.string.sync_passphrase_summary),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(horizontal = LocalAppTokens.current.screenPadding),
            )
        }

        item { SectionTitle(text = stringResource(R.string.sync_section_webdav)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.sync_webdav),
                summary = stringResource(R.string.sync_webdav_summary),
                onClick = onWebDav,
            )
        }

        item { SectionTitle(text = stringResource(R.string.sync_section_auto)) }
        item {
            AppSwitchRow(
                title = stringResource(R.string.sync_auto),
                summary = stringResource(R.string.sync_auto_summary),
                checked = draft.autoBackup,
                onCheckedChange = { onChange(draft.copy(autoBackup = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.sync_auto_wifi),
                checked = draft.autoBackupWifiOnly,
                onCheckedChange = { onChange(draft.copy(autoBackupWifiOnly = it)) },
                enabled = draft.autoBackup,
            )
        }
    }
}

@Composable
private fun StatusCard(backup: BackupStatus, onExport: () -> Unit) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(text = stringResource(R.string.dashboard_backup_title), style = AppTextStyle.Subtitle)
        if (backup.lastBackupAgo == null) {
            AppText(
                text = stringResource(R.string.dashboard_backup_never),
                style = AppTextStyle.Secondary,
                color = palette.warn,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        } else {
            AppText(
                text = stringResource(
                    R.string.dashboard_backup_last,
                    backup.lastBackupAgo,
                    backup.targetLabel ?: "",
                ),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        }
        AppTextButton(text = stringResource(R.string.dashboard_backup_now), onClick = onExport)
    }
}

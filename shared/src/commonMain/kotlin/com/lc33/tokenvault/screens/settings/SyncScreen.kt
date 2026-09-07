package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.dashboard_backup_last
import tokenvault.shared.generated.resources.dashboard_backup_never
import tokenvault.shared.generated.resources.dashboard_backup_now
import tokenvault.shared.generated.resources.dashboard_backup_title
import tokenvault.shared.generated.resources.sync_export
import tokenvault.shared.generated.resources.sync_export_summary
import tokenvault.shared.generated.resources.sync_import
import tokenvault.shared.generated.resources.sync_import_summary
import tokenvault.shared.generated.resources.sync_passphrase_summary
import tokenvault.shared.generated.resources.sync_section_local
import tokenvault.shared.generated.resources.sync_title
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 同步 —— 备份 / 恢复（计划.md §13.4、§12）。
 *
 * 仪表盘那张备份卡点进来就是这一页。
 *
 * 一条不能软化的措辞：
 * - 备份口令默认沿用 PIN，所以传到云上的包同样是**分钟级可破**（§7.6）。提示常驻，
 *   不折叠。
 *
 * WebDAV（服务器 / 凭据 / 目录配置）与「自动备份」（周期上传到 WebDAV）都是可砍项，
 * 入口已移除（`onWebDav` / `autoBackup` 空实现是撑谎）——自动备份依赖 WebDAV 作目标，
 * WebDAV 砍掉后它没有消费方。将来实现 WebDAV 四动词 + 周期备份 Worker 时再加回。
 */
@Composable
fun SyncScreen(
    backup: BackupStatus,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.sync_title, onBack = onBack) {
        item { StatusCard(backup, onExport) }

        item { SectionTitle(text = stringResource(Res.string.sync_section_local)) }
        item {
            AppArrowRow(
                title = stringResource(Res.string.sync_export),
                summary = stringResource(Res.string.sync_export_summary),
                onClick = onExport,
            )
        }
        item {
            AppArrowRow(
                title = stringResource(Res.string.sync_import),
                summary = stringResource(Res.string.sync_import_summary),
                onClick = onImport,
            )
        }
        item {
            // 备份口令默认沿用 PIN，导出/恢复时每次输入（可换任意长口令）。这里没有
            // 独立的设置页——"单独设长口令"就是导出时输一个不同于 PIN 的口令，
            // 持久化独立口令属于 WebDAV 无人值守备份（可砍），所以只留一行常驻提示
            // （§12.1），不做成一个点了没反应的箭头行。
            AppText(
                text = stringResource(Res.string.sync_passphrase_summary),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(horizontal = LocalAppTokens.current.screenPadding),
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
        AppText(text = stringResource(Res.string.dashboard_backup_title), style = AppTextStyle.Subtitle)
        val lastBackupAgo = backup.lastBackupAgo
        if (lastBackupAgo == null) {
            AppText(
                text = stringResource(Res.string.dashboard_backup_never),
                style = AppTextStyle.Secondary,
                color = palette.warn,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        } else {
            AppText(
                text = stringResource(
                    Res.string.dashboard_backup_last,
                    lastBackupAgo,
                    backup.targetLabel ?: "",
                ),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(vertical = tokens.itemSpacing),
            )
        }
        AppTextButton(text = stringResource(Res.string.dashboard_backup_now), onClick = onExport)
    }
}

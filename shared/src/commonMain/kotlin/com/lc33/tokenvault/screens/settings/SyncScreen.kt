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
import tokenvault.shared.generated.resources.sync_remote_count
import tokenvault.shared.generated.resources.sync_section_local
import tokenvault.shared.generated.resources.sync_section_webdav
import tokenvault.shared.generated.resources.sync_title
import tokenvault.shared.generated.resources.sync_webdav_refresh
import tokenvault.shared.generated.resources.sync_webdav_refresh_summary
import tokenvault.shared.generated.resources.sync_webdav_restore
import tokenvault.shared.generated.resources.sync_webdav_restore_summary
import tokenvault.shared.generated.resources.sync_webdav_settings
import tokenvault.shared.generated.resources.sync_webdav_settings_configured
import tokenvault.shared.generated.resources.sync_webdav_settings_summary
import tokenvault.shared.generated.resources.sync_webdav_upload
import tokenvault.shared.generated.resources.sync_webdav_upload_summary
import com.lc33.tokenvault.domain.model.WebDavConfig
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 同步 —— 本地备份 / 恢复与 WebDAV。
 *
 * 页面主体只有行入口；所有需要输入或确认的动作都放在弹层里。WebDAV 凭据
 * 不回显，改地址或目录时可以保留原凭据，只有输入了新值才覆盖。
 */
@Composable
fun SyncScreen(
    backup: BackupStatus,
    webDavConfig: WebDavConfig,
    webDavBusy: Boolean,
    remoteBackups: List<String>?,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenWebDavSettings: () -> Unit,
    onUploadWebDav: () -> Unit,
    onRestoreWebDav: () -> Unit,
    onRefreshWebDav: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.sync_title, onBack = onBack) {
        item { StatusCard(backup, onExport) }

        item { SectionTitle(text = stringResource(Res.string.sync_section_local)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.sync_export),
                    summary = stringResource(Res.string.sync_export_summary),
                    onClick = onExport,
                )
                AppArrowRow(
                    title = stringResource(Res.string.sync_import),
                    summary = stringResource(Res.string.sync_import_summary),
                    onClick = onImport,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.sync_section_webdav)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.sync_webdav_settings),
                    summary = stringResource(
                        if (webDavConfig.isReady) {
                            Res.string.sync_webdav_settings_configured
                        } else {
                            Res.string.sync_webdav_settings_summary
                        },
                    ),
                    onClick = onOpenWebDavSettings,
                )
                AppActionRow(
                    text = stringResource(Res.string.sync_webdav_upload),
                    onClick = onUploadWebDav,
                    enabled = webDavConfig.isReady && !webDavBusy,
                )
                AppActionRow(
                    text = stringResource(Res.string.sync_webdav_restore),
                    onClick = onRestoreWebDav,
                    enabled = webDavConfig.isReady && !webDavBusy,
                )
                AppActionRow(
                    text = stringResource(Res.string.sync_webdav_refresh),
                    onClick = onRefreshWebDav,
                    enabled = webDavConfig.isReady && !webDavBusy,
                )
            }
        }
        item {
            AppText(
                text = stringResource(Res.string.sync_remote_count, remoteBackups?.size ?: 0),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(horizontal = LocalAppTokens.current.screenPadding),
            )
        }
        item {
            // 备份口令默认沿用 PIN；WebDAV 上传前同样要输入。这里常驻提示，
            // 不折叠成“高级设置”。
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
        AppActionRow(text = stringResource(Res.string.dashboard_backup_now), onClick = onExport)
    }
}
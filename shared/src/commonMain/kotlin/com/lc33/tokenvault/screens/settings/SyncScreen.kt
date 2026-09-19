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
import tokenvault.shared.generated.resources.backup_target_local
import tokenvault.shared.generated.resources.backup_target_webdav
import tokenvault.shared.generated.resources.sync_export
import tokenvault.shared.generated.resources.sync_export_summary
import tokenvault.shared.generated.resources.sync_import
import tokenvault.shared.generated.resources.sync_import_summary
import tokenvault.shared.generated.resources.sync_passphrase_summary
import tokenvault.shared.generated.resources.sync_remote_count
import tokenvault.shared.generated.resources.sync_remote_empty
import tokenvault.shared.generated.resources.sync_remote_section
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
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.BackupStatus
import com.lc33.tokenvault.screens.model.UiBackupTarget
import com.lc33.tokenvault.screens.model.UiRemoteBackup
import com.lc33.tokenvault.ui.common.relativeLabel
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
 *
 * 顶部那张状态卡里的「立即备份」是**上下文相关**的：配置好 WebDAV 就上传到远端，
 * 没配置就导出成本地文件——同一颗按钮，永远做"把现在的库备份一份"这件事，
 * 只是落点跟着配置走。
 */
@Composable
fun SyncScreen(
    backup: BackupStatus,
    webDavConfig: WebDavConfig,
    webDavBusy: Boolean,
    remoteBackups: List<UiRemoteBackup>?,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenWebDavSettings: () -> Unit,
    onUploadWebDav: () -> Unit,
    onRestoreWebDav: () -> Unit,
    onRestoreRemote: (String) -> Unit,
    onRefreshWebDav: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    SettingsSubPage(titleRes = Res.string.sync_title, onBack = onBack) {
        item { StatusCard(backup, webDavConfig.isReady, onExport, onUploadWebDav) }

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
        // 拉到的备份逐条列在这里（2026-09 反馈：只有"恢复最新"一个入口，等于只能回到昨天）。
        // null = 还没拉取，这一段不画；空列表照画——"远端确实没有包"和"还没点刷新"
        // 是两件事，都缩成不画就分不出来了。
        if (webDavConfig.isReady && remoteBackups != null) {
            item { SectionTitle(text = stringResource(Res.string.sync_remote_section)) }
            if (remoteBackups.isEmpty()) {
                item {
                    AppText(
                        text = stringResource(Res.string.sync_remote_empty),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            } else {
                item {
                    AppPreferenceGroup {
                        remoteBackups.forEach { row ->
                            AppArrowRow(
                                title = row.label,
                                summary = row.fileName,
                                enabled = !webDavBusy,
                                onClick = { onRestoreRemote(row.fileName) },
                            )
                        }
                    }
                }
            }
        }
        item {
            // 两条说明同装一张卡：以前是两张"裸文本"，左缘比卡片内容浅 16dp，
            // 而且紧挨着排（行距为 0），看起来像漏排版的两行。
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            ) {
                AppText(
                    text = stringResource(Res.string.sync_remote_count, remoteBackups?.size ?: 0),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                )
                // 备份口令默认沿用 PIN；WebDAV 上传前同样要输入。这里常驻提示，
                // 不折叠成“高级设置”。
                AppText(
                    text = stringResource(Res.string.sync_passphrase_summary),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(top = tokens.itemSpacing),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    backup: BackupStatus,
    webDavReady: Boolean,
    onExport: () -> Unit,
    onUploadWebDav: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    // 备份状态与「立即备份」分开：描述卡只讲状态，入口单独一行，
    // 免得整张卡看起来都可点。
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(text = stringResource(Res.string.dashboard_backup_title), style = AppTextStyle.Subtitle)
        val lastBackupAtMs = backup.lastBackupAtMs
        if (lastBackupAtMs == null) {
            AppText(
                text = stringResource(Res.string.dashboard_backup_never),
                style = AppTextStyle.Secondary,
                color = palette.warn,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        } else {
            // 相对时间与落点文案都在这里现算：ViewModel 只给时间戳与枚举，
            // 字面量绝不进 UiState（红线 19）。
            val targetLabel = when (backup.target) {
                UiBackupTarget.WebDav -> stringResource(Res.string.backup_target_webdav)
                else -> stringResource(Res.string.backup_target_local)
            }
            AppText(
                text = stringResource(
                    Res.string.dashboard_backup_last,
                    relativeLabel(nowMillis(), lastBackupAtMs),
                    targetLabel,
                ),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(top = tokens.itemSpacing),
            )
        }
    }
    AppPreferenceGroup(
        modifier = Modifier.padding(horizontal = tokens.screenPadding),
        inset = false,
    ) {
        AppActionRow(
            text = stringResource(Res.string.dashboard_backup_now),
            // 配置好 WebDAV 就上传到远端，否则导出成本地文件：用户按的是"把库备份一份"，
            // 落点跟着配置走，而不是让他在两个入口里猜。
            onClick = if (webDavReady) onUploadWebDav else onExport,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
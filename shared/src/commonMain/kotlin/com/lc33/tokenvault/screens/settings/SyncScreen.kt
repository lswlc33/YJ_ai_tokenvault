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
import tokenvault.shared.generated.resources.sync_webdav_busy
import tokenvault.shared.generated.resources.sync_webdav_checking
import tokenvault.shared.generated.resources.sync_webdav_refresh
import tokenvault.shared.generated.resources.sync_webdav_restore
import tokenvault.shared.generated.resources.sync_webdav_settings
import tokenvault.shared.generated.resources.sync_webdav_settings_configured
import tokenvault.shared.generated.resources.sync_webdav_settings_summary
import tokenvault.shared.generated.resources.sync_webdav_upload
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
    /** 只读的"检查连接"正在进行，与 [webDavBusy] 分开：上传同样会灰掉那三行。 */
    webDavChecking: Boolean,
    remoteBackups: List<UiRemoteBackup>?,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenWebDavSettings: () -> Unit,
    onUploadWebDav: () -> Unit,
    onRestoreWebDav: () -> Unit,
    /** 点列表里的某一份：弹层里再决定恢复还是删除（动作由 `SyncRouteContent` 承接）。 */
    onPickRemoteBackup: (UiRemoteBackup) -> Unit,
    onRefreshWebDav: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    SettingsSubPage(titleRes = Res.string.sync_title, onBack = onBack) {
        item { StatusCard(backup, webDavConfig.isReady, webDavBusy, onExport, onUploadWebDav) }

        item { SectionTitle(text = stringResource(Res.string.sync_section_local)) }
        item {
            AppPreferenceGroup {
                AppArrowRow(
                    title = stringResource(Res.string.sync_export),
                    summary = stringResource(Res.string.sync_export_summary),
                    onClick = onExport,
                    // 与「立即备份」同一条理由：恢复是"先清库再写回"，中途导出的是一份
                    // 半成品备份，而提示只会说"已导出"。
                    enabled = !webDavBusy,
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
        // 进页面就自动拉一次列表，那几秒三行动作是灰的。以前灰得没有理由：既不说明
        // "在等什么"，也不说明"马上就好"（2026-09 反馈：从灰显到可用中间缺一句提示）。
        // 那句话当时只挂在"检查连接"这一档上，而灰掉这三行的是**六个**动作
        // （上传、恢复、删除远端、存配置、拉列表、以及恢复期间的导出）——拉列表之外的
        // 任何一发跑起来时，页面照样是一片灰而没有一句解释，读起来像坏了。
        // 检查那一档单独留着：它已经有精确文案，其余共用这一句中性说法。
        if (webDavChecking || webDavBusy) {
            item {
                AppText(
                    text = if (webDavChecking) {
                        stringResource(Res.string.sync_webdav_checking)
                    } else {
                        stringResource(Res.string.sync_webdav_busy)
                    },
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(
                        start = tokens.screenPadding,
                        end = tokens.screenPadding,
                        top = tokens.itemSpacing,
                    ),
                )
            }
        }
        // 拉到的备份逐条列在这里（2026-09 反馈：只有"恢复最新"一个入口，等于只能回到昨天）。
        // null = 还没拉取，这一段不画；空列表照画——"远端确实没有包"和"还没点刷新"
        // 是两件事，都缩成不画就分不出来了。
        // 点了不直接恢复，而是弹层里再挑恢复还是删除（2026-09 反馈）：清掉某一份传错了的
        // 包以前在应用里没有入口，而"点一下就开始恢复"又让误触的代价变成整库。
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
                                onClick = { onPickRemoteBackup(row) },
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
    /** 有一发 WebDAV 动作在跑（尤其是恢复）：这时不许多导出一份库。 */
    busy: Boolean,
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
            //
            // 恢复进行中必须挡住这一发：OVERWRITE 那一种恢复是"先清库、再写回"，中间
            // 读一次快照导出的就是一份写了一半的库，而提示照样会说"已导出"——这份备份
            // 看着是安全的，真拿去恢复时才发现有半数数据不在里面。
            onClick = if (webDavReady) onUploadWebDav else onExport,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
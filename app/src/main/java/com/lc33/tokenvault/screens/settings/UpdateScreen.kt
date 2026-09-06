package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.BuildConfig
import com.lc33.tokenvault.R
import com.lc33.tokenvault.engine.UpdateErrorKind
import com.lc33.tokenvault.screens.model.SettingsDraft
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.shell.UpdateViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import java.time.Instant

/**
 * 更新（计划.md §13.4）。
 *
 * 三条诚实声明必须写在页面上，不是脚注：
 * 1. 自动检查**默认关**。
 * 2. 检查只发一个匿名请求，不带设备信息、不带任何标识。
 * 3. **不做应用内静默安装**——那需要 `REQUEST_INSTALL_PACKAGES`，一个管密钥的应用
 *    去要安装权限，对威胁模型的破坏远超收益（§7.6）。下载与安装交给浏览器和系统
 *    安装器，这一点要说明，别让用户等一个不会发生的自动更新。
 */
@Composable
fun UpdateScreen(
    draft: SettingsDraft,
    onChange: (SettingsDraft) -> Unit,
    onBack: () -> Unit,
    updateState: UpdateViewModel.UiState,
    onCheckNow: (Int) -> Unit,
    onOpenDownload: (String) -> Unit,
) {
    SettingsSubPage(titleRes = R.string.update_title, onBack = onBack) {
        item {
            VersionCard(
                updateState = updateState,
                onCheckNow = { onCheckNow(draft.updateChannelIndex) },
                onOpenDownload = onOpenDownload,
            )
        }

        item { SectionTitle(text = stringResource(R.string.update_section_channel)) }
        item {
            AppDropdownRow(
                title = stringResource(R.string.update_channel),
                summary = stringResource(R.string.update_channel_summary),
                items = stringArrayResource(R.array.update_channels).toList(),
                selectedIndex = draft.updateChannelIndex,
                onSelect = { onChange(draft.copy(updateChannelIndex = it)) },
            )
        }
        item {
            AppSwitchRow(
                title = stringResource(R.string.update_auto_check),
                summary = stringResource(R.string.update_auto_check_summary),
                checked = draft.autoCheckUpdate,
                onCheckedChange = { onChange(draft.copy(autoCheckUpdate = it)) },
            )
        }

        item { SectionTitle(text = stringResource(R.string.update_section_honesty)) }
        item { HonestyCard() }
    }
}

@Composable
private fun VersionCard(
    updateState: UpdateViewModel.UiState,
    onCheckNow: () -> Unit,
    onOpenDownload: (String) -> Unit,
) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(text = stringResource(R.string.app_name), style = AppTextStyle.Subtitle)
        AppText(
            text = stringResource(
                R.string.about_version,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(vertical = tokens.itemSpacing),
        )

        when (updateState.phase) {
            UpdateViewModel.Phase.CHECKING -> {
                AppText(
                    text = stringResource(R.string.update_checking),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(vertical = tokens.itemSpacing),
                )
            }

            UpdateViewModel.Phase.UPDATE_AVAILABLE -> {
                updateState.latest?.let { latest ->
                    CheckResultCard(latest = latest, onOpenDownload = onOpenDownload)
                }
            }

            UpdateViewModel.Phase.UP_TO_DATE -> {
                AppText(
                    text = stringResource(R.string.update_up_to_date),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(vertical = tokens.itemSpacing),
                )
            }

            UpdateViewModel.Phase.ERROR -> {
                val message = when (updateState.error) {
                    UpdateErrorKind.NO_NETWORK -> stringResource(R.string.update_error_no_network)
                    else -> stringResource(R.string.update_error_unreachable)
                }
                AppText(
                    text = message,
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(vertical = tokens.itemSpacing),
                )
            }

            UpdateViewModel.Phase.IDLE -> Unit
        }

        // 检查中禁用按钮，防连点；其余时候都能再点一次。
        AppTextButton(
            text = stringResource(R.string.update_check_now),
            onClick = onCheckNow,
            enabled = updateState.phase != UpdateViewModel.Phase.CHECKING,
        )
    }
}

/** 有更新时的结果区：版本号 + 发布时间 + 更新日志 + 去下载。 */
@Composable
private fun CheckResultCard(
    latest: com.lc33.tokenvault.update.ReleaseInfo,
    onOpenDownload: (String) -> Unit,
) {
    val tokens = LocalAppTokens.current
    // 页面级时间快照（与 LogScreen 同款）：重组时不要重复读时钟，相对时间用同一基准。
    val now = remember { System.currentTimeMillis() }

    // 最新版本号：正式版 tag 是 `v0.1.0`，剥掉 `v` 前缀展示；nightly 直接展示 tag。
    val versionLabel = com.lc33.tokenvault.update.ReleaseMatcher.versionNameFromTag(latest.tagName)
        ?: latest.tagName

    AppText(
        text = stringResource(R.string.update_available, versionLabel),
        style = AppTextStyle.Body,
        modifier = Modifier.padding(vertical = tokens.itemSpacing),
    )

    latest.publishedAt?.let { iso ->
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()?.let { publishedMs ->
            AppText(
                text = stringResource(R.string.update_published, relativeLabel(now, publishedMs)),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }

    latest.body?.takeIf { it.isNotBlank() }?.let { body ->
        AppText(
            text = body,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
    }

    // 去下载：打开 release 的网页地址（API 直接给的 html_url）。
    latest.htmlUrl?.let { url ->
        AppTextButton(
            text = stringResource(R.string.update_open_download),
            onClick = { onOpenDownload(url) },
            modifier = Modifier.padding(top = tokens.itemSpacing),
        )
    }
}

@Composable
private fun HonestyCard() {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        listOf(
            R.string.update_honesty_default_off,
            R.string.update_honesty_anonymous,
            R.string.update_honesty_no_silent_install,
        ).forEach { res ->
            AppText(
                text = stringResource(res),
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

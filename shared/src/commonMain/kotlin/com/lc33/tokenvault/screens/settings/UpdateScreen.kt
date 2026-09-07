package com.lc33.tokenvault.screens.settings

import com.lc33.tokenvault.platform.nowMillis

import com.lc33.tokenvault.platform.APP_VERSION_CODE
import com.lc33.tokenvault.platform.APP_VERSION_NAME
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.about_version
import tokenvault.shared.generated.resources.app_name
import tokenvault.shared.generated.resources.update_available
import tokenvault.shared.generated.resources.update_channel
import tokenvault.shared.generated.resources.update_channel_summary
import tokenvault.shared.generated.resources.update_channels
import tokenvault.shared.generated.resources.update_check_now
import tokenvault.shared.generated.resources.update_checking
import tokenvault.shared.generated.resources.update_error_no_network
import tokenvault.shared.generated.resources.update_error_unreachable
import tokenvault.shared.generated.resources.update_honesty_anonymous
import tokenvault.shared.generated.resources.update_honesty_default_off
import tokenvault.shared.generated.resources.update_honesty_no_silent_install
import tokenvault.shared.generated.resources.update_open_download
import tokenvault.shared.generated.resources.update_published
import tokenvault.shared.generated.resources.update_section_channel
import tokenvault.shared.generated.resources.update_section_honesty
import tokenvault.shared.generated.resources.update_title
import tokenvault.shared.generated.resources.update_up_to_date
import com.lc33.tokenvault.engine.UpdateErrorKind
import com.lc33.tokenvault.ui.common.relativeLabel
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextButton
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.shell.UpdateViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import kotlinx.datetime.Instant

/**
 * 更新（计划.md §13.4）。
 *
 * 三条诚实声明必须写在页面上，不是脚注：
 * 1. **没有自动检查**——检查只在用户点「立即检查」时发一次。自动检查的定时调度
 *    未实现，所以不画那个「自动检查」开关（画了拨动没效果是撑谎）。
 * 2. 检查只发一个匿名请求，不带设备信息、不带任何标识。
 * 3. **不做应用内静默安装**——那需要 `REQUEST_INSTALL_PACKAGES`，一个管密钥的应用
 *    去要安装权限，对威胁模型的破坏远超收益（§7.6）。下载与安装交给浏览器和系统
 *    安装器，这一点要说明，别让用户等一个不会发生的自动更新。
 */
@Composable
fun UpdateScreen(
    onBack: () -> Unit,
    updateState: UpdateViewModel.UiState,
    updateChannel: Int,
    onUpdateChannelChange: (Int) -> Unit,
    onCheckNow: () -> Unit,
    onOpenDownload: (String) -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.update_title, onBack = onBack) {
        item {
            VersionCard(
                updateState = updateState,
                onCheckNow = onCheckNow,
                onOpenDownload = onOpenDownload,
            )
        }

        item { SectionTitle(text = stringResource(Res.string.update_section_channel)) }
        item {
            AppPreferenceGroup {
                AppDropdownRow(
                    title = stringResource(Res.string.update_channel),
                    summary = stringResource(Res.string.update_channel_summary),
                    items = stringArrayResource(Res.array.update_channels).toList(),
                    selectedIndex = updateChannel,
                    onSelect = onUpdateChannelChange,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.update_section_honesty)) }
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
        AppText(text = stringResource(Res.string.app_name), style = AppTextStyle.Subtitle)
        AppText(
            text = stringResource(
                Res.string.about_version,
                APP_VERSION_NAME,
                APP_VERSION_CODE,
            ),
            style = AppTextStyle.Secondary,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(vertical = tokens.itemSpacing),
        )

        when (updateState.phase) {
            UpdateViewModel.Phase.CHECKING -> {
                AppText(
                    text = stringResource(Res.string.update_checking),
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
                    text = stringResource(Res.string.update_up_to_date),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(vertical = tokens.itemSpacing),
                )
            }

            UpdateViewModel.Phase.ERROR -> {
                val message = when (updateState.error) {
                    UpdateErrorKind.NO_NETWORK -> stringResource(Res.string.update_error_no_network)
                    else -> stringResource(Res.string.update_error_unreachable)
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
            text = stringResource(Res.string.update_check_now),
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
    val now = remember { nowMillis() }

    // 最新版本号：正式版 tag 是 `v0.1.0`，剥掉 `v` 前缀展示；nightly 直接展示 tag。
    val versionLabel = com.lc33.tokenvault.update.ReleaseMatcher.versionNameFromTag(latest.tagName)
        ?: latest.tagName

    AppText(
        text = stringResource(Res.string.update_available, versionLabel),
        style = AppTextStyle.Body,
        modifier = Modifier.padding(vertical = tokens.itemSpacing),
    )

    latest.publishedAt?.let { iso ->
        runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()?.let { publishedMs ->
            AppText(
                text = stringResource(Res.string.update_published, relativeLabel(now, publishedMs)),
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
            text = stringResource(Res.string.update_open_download),
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
            Res.string.update_honesty_default_off,
            Res.string.update_honesty_anonymous,
            Res.string.update_honesty_no_silent_install,
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

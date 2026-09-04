package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.BuildConfig
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.SettingsDraft
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
    onCheckNow: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.update_title, onBack = onBack) {
        item { VersionCard(onCheckNow) }

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
private fun VersionCard(onCheckNow: () -> Unit) {
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
        AppTextButton(text = stringResource(R.string.update_check_now), onClick = onCheckNow)
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

package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.R
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/** 一条客户端预设在列表里的样子。 */
data class ProfileRow(
    val id: Long?,
    val name: String,
    val userAgent: String,
    val builtin: Boolean,
    val verified: Boolean,
)

/**
 * 客户端预设列表（计划.md §13.4、§8.2）。
 *
 * 「已实测」那个角标是有分量的：M0.5 之后 `claude_code` 是唯一被真实中转站验证过
 * 能过闸的预设（Agent Router，而且只需要换 UA）。其余预设的头部集合来自社区观察、
 * 没有官方文档、随客户端升级漂移——这句话要能在页面上被看到，不能只写在计划里。
 */
@Composable
fun ProfileListScreen(
    profiles: List<ProfileRow>,
    onBack: () -> Unit,
    onOpenProfile: (Long?) -> Unit,
    onNewFromCurl: () -> Unit,
) {
    SettingsSubPage(titleRes = R.string.settings_profiles, onBack = onBack) {
        item { DisclaimerCard() }

        item { SectionTitle(text = stringResource(R.string.profiles_section_builtin)) }
        items(profiles.size) { index ->
            val profile = profiles[index]
            ProfileCard(profile, onClick = { onOpenProfile(profile.id) })
        }

        item { SectionTitle(text = stringResource(R.string.profiles_section_add)) }
        item {
            AppArrowRow(
                title = stringResource(R.string.profiles_from_curl),
                summary = stringResource(R.string.profiles_from_curl_summary),
                onClick = onNewFromCurl,
            )
        }
    }
}

@Composable
private fun DisclaimerCard() {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
    ) {
        AppText(
            text = stringResource(R.string.profiles_disclaimer),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
        )
    }
}

@Composable
private fun ProfileCard(profile: ProfileRow, onClick: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = 4.dp),
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText(
                text = profile.name,
                style = AppTextStyle.Body,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (profile.verified) AppChip(text = stringResource(R.string.profiles_verified))
            if (!profile.builtin) AppChip(text = stringResource(R.string.profiles_custom))
        }
        AppText(
            text = profile.userAgent,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
        )
    }
}

package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.profiles_custom
import tokenvault.shared.generated.resources.profiles_disclaimer
import tokenvault.shared.generated.resources.profiles_from_curl
import tokenvault.shared.generated.resources.profiles_from_curl_summary
import tokenvault.shared.generated.resources.profiles_section_add
import tokenvault.shared.generated.resources.profiles_section_builtin
import tokenvault.shared.generated.resources.profiles_verified
import tokenvault.shared.generated.resources.settings_profiles
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.shell.displayName
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 客户端预设列表（计划.md §13.4、§8.2）。
 *
 * 吃真数据（[ClientProfile]），不再用 [SampleContent] 的样例——内置预设是数据不是代码
 * （红线 22），seed 由 `ProfileSeeder` 在启动时种进 `client_profiles` 表。
 *
 * 「已实测」那个角标是有分量的：M0.5 之后 `claude_code` 是唯一被真实中转站验证过
 * 能过闸的预设（Agent Router，而且只需要换 UA）。其余预设的头部集合来自社区观察、
 * 没有官方文档、随客户端升级漂移——这句话要能在页面上被看到，不能只写在计划里。
 *
 * @param defaultName 内置 `default` 那枚的本地化显示名（其余内置是品牌名、自定义是用户起的名字，
 *   都不需要本地化）。由调用方用 `stringResource` 取好传进来——本页读资源没问题，但
 *   把它当参数能让"显示名怎么定"这件事集中在一处（[displayName]）。
 */
@Composable
fun ProfileListScreen(
    profiles: List<ClientProfile>,
    defaultName: String,
    onBack: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onNewFromCurl: () -> Unit,
) {
    SettingsSubPage(titleRes = Res.string.settings_profiles, onBack = onBack) {
        item { DisclaimerCard() }

        item { SectionTitle(text = stringResource(Res.string.profiles_section_builtin)) }
        items(profiles.size) { index ->
            val profile = profiles[index]
            ProfileCard(profile, defaultName, onClick = { onOpenProfile(profile.id) })
        }

        item { SectionTitle(text = stringResource(Res.string.profiles_section_add)) }
        item {
            AppArrowRow(
                title = stringResource(Res.string.profiles_from_curl),
                summary = stringResource(Res.string.profiles_from_curl_summary),
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
            text = stringResource(Res.string.profiles_disclaimer),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
        )
    }
}

@Composable
private fun ProfileCard(profile: ClientProfile, defaultName: String, onClick: () -> Unit) {
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
                text = profile.displayName(defaultName),
                style = AppTextStyle.Body,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (profile.verified) AppChip(text = stringResource(Res.string.profiles_verified))
            if (profile.builtinKey == null) AppChip(text = stringResource(Res.string.profiles_custom))
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

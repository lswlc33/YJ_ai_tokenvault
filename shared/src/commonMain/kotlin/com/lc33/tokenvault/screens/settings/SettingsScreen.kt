package com.lc33.tokenvault.screens.settings

import com.lc33.tokenvault.platform.APP_VERSION_NAME
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.settings_about_row
import tokenvault.shared.generated.resources.settings_about_row_summary
import tokenvault.shared.generated.resources.settings_appearance
import tokenvault.shared.generated.resources.settings_appearance_summary
import tokenvault.shared.generated.resources.settings_data
import tokenvault.shared.generated.resources.settings_data_summary
import tokenvault.shared.generated.resources.settings_log
import tokenvault.shared.generated.resources.settings_log_summary
import tokenvault.shared.generated.resources.settings_member_expiry
import tokenvault.shared.generated.resources.settings_member_title
import tokenvault.shared.generated.resources.settings_group_about
import tokenvault.shared.generated.resources.settings_group_settings
import tokenvault.shared.generated.resources.settings_group_sync
import tokenvault.shared.generated.resources.settings_group_update
import tokenvault.shared.generated.resources.settings_probe
import tokenvault.shared.generated.resources.settings_probe_summary
import tokenvault.shared.generated.resources.settings_profiles
import tokenvault.shared.generated.resources.settings_profiles_summary
import tokenvault.shared.generated.resources.settings_security
import tokenvault.shared.generated.resources.settings_security_summary
import tokenvault.shared.generated.resources.settings_sync_row
import tokenvault.shared.generated.resources.settings_sync_row_summary
import tokenvault.shared.generated.resources.settings_title
import tokenvault.shared.generated.resources.settings_update_row
import tokenvault.shared.generated.resources.settings_update_row_summary
import com.lc33.tokenvault.ui.miuix.AppAccentCard
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconTint
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appOnPrimaryColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 设置 —— 软件自身的配置（计划.md §13.4）。
 *
 * 这一页是**导航面板**而不是巨型表单：原来七组挤在一页要滚四五屏，而其中大半
 * 是一年碰一次的东西。四块分别是设置 / 同步 / 关于 / 更新，具体项都在二级页。
 */
@Composable
fun SettingsScreen(
    onOpenAppearance: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenProbeSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenData: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.settings_title),
                scrollState = scrollState,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item {
                MemberCard(
                    modifier = Modifier.padding(
                        start = tokens.screenPadding,
                        end = tokens.screenPadding,
                        top = tokens.itemSpacing,
                        bottom = tokens.itemSpacing,
                    ),
                )
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_settings)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_appearance),
                        summary = stringResource(Res.string.settings_appearance_summary),
                        onClick = onOpenAppearance,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_security),
                        summary = stringResource(Res.string.settings_security_summary),
                        onClick = onOpenSecurity,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_probe),
                        summary = stringResource(Res.string.settings_probe_summary),
                        onClick = onOpenProbeSettings,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_profiles),
                        summary = stringResource(Res.string.settings_profiles_summary),
                        onClick = onOpenProfiles,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_data),
                        summary = stringResource(Res.string.settings_data_summary),
                        onClick = onOpenData,
                    )
                    AppArrowRow(
                        title = stringResource(Res.string.settings_log),
                        summary = stringResource(Res.string.settings_log_summary),
                        onClick = onOpenLog,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_sync)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_sync_row),
                        summary = stringResource(Res.string.settings_sync_row_summary),
                        onClick = onOpenSync,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_about)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_about_row),
                        summary = stringResource(Res.string.settings_about_row_summary),
                        onClick = onOpenAbout,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.settings_group_update)) }
            item {
                AppPreferenceGroup {
                    AppArrowRow(
                        title = stringResource(Res.string.settings_update_row),
                        summary = stringResource(
                            Res.string.settings_update_row_summary,
                            APP_VERSION_NAME,
                        ),
                        onClick = onOpenUpdate,
                    )
                }
            }

            // 滑到底的呼吸空间：内容画到窗口底部（透出玻璃底栏），不垫就会贴边。
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

/**
 * 设置页开头的会员卡 —— **纯装饰，没有任何实际用途**。
 *
 * 它不接任何数据、不可点击、点进去也没有页面。存在的意义只是给用户一点情绪价值，
 * 所以刻意不做成 [AppArrowRow]：带箭头的行会暗示"点进去还有内容"，而这里没有。
 *
 * 展示的信息（标题与过期时间）来自资源，不是硬编码；"2099-99-99" 是个不可能的日期，
 * 用来表达"永久"，不要把它当成真实有效期去解析。
 */
@Composable
private fun MemberCard(modifier: Modifier = Modifier) {
    val tokens = LocalAppTokens.current
    AppAccentCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppIconTint(
                icon = AppIcon.Member,
                modifier = Modifier.size(28.dp),
                tint = appOnPrimaryColor,
            )
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = stringResource(Res.string.settings_member_title),
                    style = AppTextStyle.Subtitle,
                    color = appOnPrimaryColor,
                )
                AppText(
                    text = stringResource(Res.string.settings_member_expiry),
                    style = AppTextStyle.Footnote,
                    color = appOnPrimaryColor,
                )
            }
        }
    }
}

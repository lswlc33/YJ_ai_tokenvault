package com.lc33.tokenvault.screens.settings

import com.lc33.tokenvault.platform.APP_VERSION_NAME
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import tokenvault.shared.generated.resources.settings_member_hint
import tokenvault.shared.generated.resources.settings_member_idle_summary
import tokenvault.shared.generated.resources.settings_member_title
import tokenvault.shared.generated.resources.member_badge
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
import com.lc33.tokenvault.ui.miuix.AppArrowRow
import com.lc33.tokenvault.ui.miuix.AppMemberCard
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 设置 —— 软件自身的配置（计划.md §13.4）。
 *
 * 这一页是**导航面板**而不是巨型表单：原来七组挤在一页要滚四五屏，而其中大半
 * 是一年碰一次的东西。四块分别是设置 / 同步 / 关于 / 更新，具体项都在二级页。
 *
 * 顶部那张会员卡是**纯娱乐的展示**：轻点进会员介绍页，会员长按 5 秒退回普通用户。
 * 它不接任何权限，[member] 只决定画哪一种卡面（见 `MemberViewModel`）。
 */
@Composable
fun SettingsScreen(
    member: Boolean,
    onOpenAppearance: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenProbeSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenData: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenMember: () -> Unit,
    onRevertMember: () -> Unit,
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
                AppMemberCard(
                    member = member,
                    title = stringResource(Res.string.settings_member_title),
                    subtitle = stringResource(
                        if (member) {
                            Res.string.settings_member_expiry
                        } else {
                            Res.string.settings_member_idle_summary
                        },
                    ),
                    badge = stringResource(Res.string.member_badge),
                    hint = stringResource(Res.string.settings_member_hint),
                    onOpen = onOpenMember,
                    onRevert = onRevertMember,
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
 * 会员卡（娱乐功能）已挪到 `ui/miuix/AppMemberCard.kt`：它会随"是不是会员"在两种卡面之间
 * 切换，并带一个 5 秒长按手势，所以需要 MIUIX 那一层的 `Haptics` 与主题色，
 * 而不该在页面里拼。`SettingsScreen` 只负责把状态和两个回调递进去。
 */

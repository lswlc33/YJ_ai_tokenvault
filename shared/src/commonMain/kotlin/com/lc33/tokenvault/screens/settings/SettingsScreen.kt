package com.lc33.tokenvault.screens.settings

import com.lc33.tokenvault.platform.APP_VERSION_NAME
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import tokenvault.shared.generated.resources.member_expiry_countdown
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
import com.lc33.tokenvault.ui.shell.MemberCountdown
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 设置 —— 软件自身的配置（计划.md §13.4）。
 *
 * 这一页是**导航面板**而不是巨型表单：原来七组挤在一页要滚四五屏，而其中大半
 * 是一年碰一次的东西。四块分别是设置 / 同步 / 关于 / 更新，具体项都在二级页。
 *
 * 顶部那张会员卡是**纯娱乐的展示**：普通用户轻点进会员介绍页；会员每点一次
 * 到期时间减 10 年，点满 10 次取消会员身份。计数是页面级状态——切走标签页或
 * 重启应用都会清零（pager 会销毁离屏页的组合），于是"再次进入设置回到初始"
 * 不用额外记账。不承载任何权限，[member] 只决定画哪一种卡面（见 `MemberViewModel`）。
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
    // 会员卡"点一次减 10 年"的计数。刻意用 remember 而不是 rememberSaveable：
    // 切走标签页 / 重启都要回到初始（这是娱乐，不该被记住）。
    var memberTapCount by remember { mutableStateOf(0) }
    val memberExpiryYear = MemberCountdown.expiryYear(memberTapCount)
    val onMemberCardTap = {
        if (!member) {
            onOpenMember()
        } else if (MemberCountdown.shouldRevert(memberTapCount + 1)) {
            memberTapCount = 0
            onRevertMember()
        } else {
            memberTapCount += 1
        }
    }
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
                        when {
                            !member -> Res.string.settings_member_idle_summary
                            memberTapCount == 0 -> Res.string.settings_member_expiry
                            else -> Res.string.member_expiry_countdown
                        },
                        memberExpiryYear,
                    ),
                    badge = stringResource(Res.string.member_badge),
                    hint = stringResource(Res.string.settings_member_hint),
                    onClick = onMemberCardTap,
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

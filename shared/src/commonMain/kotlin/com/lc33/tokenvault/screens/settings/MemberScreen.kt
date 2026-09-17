package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.member_badge
import tokenvault.shared.generated.resources.member_buy_free
import tokenvault.shared.generated.resources.member_cancel
import tokenvault.shared.generated.resources.member_disclaimer
import tokenvault.shared.generated.resources.member_hero_expiry
import tokenvault.shared.generated.resources.member_hero_subtitle
import tokenvault.shared.generated.resources.member_hero_title
import tokenvault.shared.generated.resources.member_page_title
import tokenvault.shared.generated.resources.member_privilege_card
import tokenvault.shared.generated.resources.member_privilege_card_summary
import tokenvault.shared.generated.resources.member_privilege_flow
import tokenvault.shared.generated.resources.member_privilege_flow_summary
import tokenvault.shared.generated.resources.member_privilege_security
import tokenvault.shared.generated.resources.member_privilege_security_summary
import tokenvault.shared.generated.resources.member_privilege_status
import tokenvault.shared.generated.resources.member_privilege_status_summary
import tokenvault.shared.generated.resources.member_privileges_section
import com.lc33.tokenvault.ui.miuix.AppActionButton
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconTint
import com.lc33.tokenvault.ui.miuix.AppMemberHero
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appPrimaryColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 会员介绍页（娱乐功能）。
 *
 * 说清楚它是什么：**这一页上所有"特权"都只有展示作用**。代码里没有任何一处根据
 * "是不是会员"改变行为——没有少收一次请求、没有多开一条并发、没有改一次加密。
 * 页面底部那行 [Res.string.member_disclaimer] 就是把这个事实写给用户看，
 * 而不是让他在设置里到处找"会员到底给了什么"。另一半的说法在 `MemberViewModel` 的注释里。
 *
 * 两个按钮：
 * - 「限时免费」= 翻成会员。已经是会员时置灰，避免"点了没反应"的错觉。
 * - 「取消」= 退回普通用户。本来不是会员时置灰。
 *
 * 用 [AppActionButton] 而不是行入口：这是"同一行里两个等重动作"，正是那个组件存在的理由
 * （页面主体不允许出现按钮堆，这一处是显式列出的例外）。
 */
@Composable
fun MemberScreen(
    member: Boolean,
    onBack: () -> Unit,
    onPurchase: () -> Unit,
    onCancel: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    SettingsSubPage(titleRes = Res.string.member_page_title, onBack = onBack) {
        item {
            AppMemberHero(
                member = member,
                title = stringResource(Res.string.member_hero_title),
                subtitle = stringResource(Res.string.member_hero_subtitle),
                expiry = stringResource(Res.string.member_hero_expiry),
                badge = stringResource(Res.string.member_badge),
                modifier = Modifier.padding(
                    start = tokens.screenPadding,
                    end = tokens.screenPadding,
                    top = tokens.itemSpacing,
                ),
            )
        }

        item { SectionTitle(text = stringResource(Res.string.member_privileges_section)) }
        item {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding),
            ) {
                MemberPrivilege(
                    icon = AppIcon.Member,
                    title = stringResource(Res.string.member_privilege_card),
                    summary = stringResource(Res.string.member_privilege_card_summary),
                )
                MemberPrivilege(
                    icon = AppIcon.Ok,
                    title = stringResource(Res.string.member_privilege_status),
                    summary = stringResource(Res.string.member_privilege_status_summary),
                )
                MemberPrivilege(
                    icon = AppIcon.Refresh,
                    title = stringResource(Res.string.member_privilege_flow),
                    summary = stringResource(Res.string.member_privilege_flow_summary),
                )
                MemberPrivilege(
                    icon = AppIcon.Locked,
                    title = stringResource(Res.string.member_privilege_security),
                    summary = stringResource(Res.string.member_privilege_security_summary),
                )
                AppText(
                    text = stringResource(Res.string.member_disclaimer),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(top = tokens.itemSpacing),
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                AppActionButton(
                    text = stringResource(Res.string.member_cancel),
                    onClick = onCancel,
                    enabled = member,
                    modifier = Modifier.weight(1f),
                )
                AppActionButton(
                    text = stringResource(Res.string.member_buy_free),
                    onClick = onPurchase,
                    enabled = !member,
                    primary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 一条会员特权。左边图标、右边标题 + 说明，纯展示、不可点。 */
@Composable
private fun MemberPrivilege(
    icon: AppIcon,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = tokens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        verticalAlignment = Alignment.Top,
    ) {
        AppIconTint(
            icon = icon,
            modifier = Modifier.size(22.dp),
            tint = appPrimaryColor,
        )
        Column(modifier = Modifier.weight(1f)) {
            AppText(text = title, style = AppTextStyle.Body)
            AppText(
                text = summary,
                style = AppTextStyle.Footnote,
                color = appSecondaryTextColor,
            )
        }
    }
}

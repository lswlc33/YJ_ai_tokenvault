package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.log_detail_body_empty
import tokenvault.shared.generated.resources.log_detail_request_body
import tokenvault.shared.generated.resources.log_detail_request_url
import tokenvault.shared.generated.resources.log_detail_response_body
import tokenvault.shared.generated.resources.log_detail_title
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.ui.common.StatusDot
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.AppValueRow
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 一条网络请求的完整报文：地址 / 请求体 / 返回体。
 *
 * 三段都是**原文**（入库前已过脱敏，见 `Redactor`），所以用等宽 + 可选中阅读的排版，
 * 而不是再压成一句话。响应可能是几十 KB 的 JSON，整段铺开会让这一页很长——报文本身
 * 就长，这里不做折叠：折叠了还得再教用户"点一下能展开"，而这一页的存在意义就是看原文。
 */
@Composable
fun LogDetailScreen(
    entry: AuditEntry?,
    onBack: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.log_detail_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        // 记录可能在打开这一页之前被清理掉了（保留期到了 / 用户清了日志）。
        // 那时给一句"这条记录已经不在了"，而不是一个空白页。
        if (entry == null) {
            AppText(
                text = stringResource(Res.string.log_detail_body_empty),
                style = AppTextStyle.Secondary,
                color = appSecondaryTextColor,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = tokens.screenPadding),
            )
            return@AppScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            // 摘要卡：这一条是什么请求、结果如何。整句 message 已经是"http GET host/path -> 200"
            // 的形态，所以直接给状态点 + 原文，不另拆字段。
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    StatusDot(
                        color = levelColor(entry.level, LocalStatusPalette.current),
                        label = stringResource(levelLabelRes(entry.level)),
                    )
                    AppText(
                        text = entry.message,
                        style = AppTextStyle.Body,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    entry.detail?.let { detail ->
                        AppText(
                            text = detail,
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }

            item {
                AppPreferenceGroup(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    inset = false,
                ) {
                    AppValueRow(
                        title = stringResource(Res.string.log_detail_request_url),
                        value = entry.requestUrl.orEmpty(),
                        stacked = true,
                        mono = true,
                    )
                }
            }

            item {
                AppPreferenceGroup(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    inset = false,
                ) {
                    AppValueRow(
                        title = stringResource(Res.string.log_detail_response_body),
                        value = entry.responseBody
                            ?: stringResource(Res.string.log_detail_body_empty),
                        stacked = true,
                        mono = true,
                    )
                }
            }

            item {
                AppPreferenceGroup(
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    inset = false,
                ) {
                    AppValueRow(
                        title = stringResource(Res.string.log_detail_request_body),
                        value = entry.requestBody
                            ?: stringResource(Res.string.log_detail_body_empty),
                        stacked = true,
                        mono = true,
                    )
                }
            }

            // 滑到底的呼吸空间：内容画到窗口底部（透出玻璃底栏），不垫就会贴边。
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

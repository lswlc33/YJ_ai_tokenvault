package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.probe_keywords
import tokenvault.shared.generated.resources.probe_keywords_add
import tokenvault.shared.generated.resources.probe_keywords_delete
import tokenvault.shared.generated.resources.probe_keywords_empty
import tokenvault.shared.generated.resources.probe_keywords_hint
import tokenvault.shared.generated.resources.probe_thresholds_save
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.shell.ClientKeywordsViewModel
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 客户端拦截关键词编辑（§8.2）。
 *
 * 关键词匹配上游响应的协议内容（不是 UI 文案，i18n-exempt），命中任一判 `CLIENT_BLOCKED`。
 * 增删都在内存里改一份副本，点「保存」才写库。空列表表示回到默认。
 */
@Composable
fun ClientKeywordsScreen(
    viewModel: ClientKeywordsViewModel,
    onBack: () -> Unit,
) {
    val loaded by viewModel.keywords.collectAsStateWithLifecycle()

    // 与 BalanceThresholdsScreen 同一套规则：Room 首帧异步，没读到前不建列表状态。
    val initial = loaded ?: return

    // 可编辑副本。初值取已存列表（可能为空 = 用默认）。
    var items by remember(initial) { mutableStateOf(initial.toList()) }
    val input = rememberAppTextFieldState("")
    val tokens = LocalAppTokens.current
    val deleteDesc = stringResource(Res.string.probe_keywords_delete)

    SettingsSubPage(titleRes = Res.string.probe_keywords, onBack = onBack) {
        item {
            AppCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            ) {
                AppText(
                    text = stringResource(Res.string.probe_keywords_hint),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                )
            }
        }

        item { SectionTitle(text = stringResource(Res.string.probe_keywords)) }

        if (items.isEmpty()) {
            item {
                AppText(
                    text = stringResource(Res.string.probe_keywords_empty),
                    style = AppTextStyle.Secondary,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }
        } else {
            items.forEach { keyword ->
                item(key = keyword) {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(
                                text = keyword,
                                style = AppTextStyle.Body,
                                modifier = Modifier.weight(1f),
                            )
                            AppIconButton(
                                icon = AppIcon.Delete,
                                contentDescription = deleteDesc,
                                onClick = { items = items - keyword },
                            )
                        }
                    }
                }
            }
        }

        item {
            AppTextField(
                state = input,
                label = stringResource(Res.string.probe_keywords_add),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
            )
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppActionRow(
                    text = stringResource(Res.string.probe_keywords_add),
                    onClick = {
                        val kw = input.text.trim()
                        if (kw.isNotEmpty() && kw !in items) {
                            items = items + kw
                            input.clear()
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
                AppActionRow(
                    text = stringResource(Res.string.probe_thresholds_save),
                    onClick = {
                        viewModel.save(items)
                        onBack()
                    },
                )
            }
        }
    }
}

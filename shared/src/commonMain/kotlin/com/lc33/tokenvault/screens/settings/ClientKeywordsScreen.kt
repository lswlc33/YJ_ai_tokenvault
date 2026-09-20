package com.lc33.tokenvault.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lc33.tokenvault.ui.common.LoadingState
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.probe_keywords
import tokenvault.shared.generated.resources.probe_keywords_add
import tokenvault.shared.generated.resources.probe_keywords_delete
import tokenvault.shared.generated.resources.probe_keywords_empty
import tokenvault.shared.generated.resources.probe_keywords_hint
import tokenvault.shared.generated.resources.probe_thresholds_save
import com.lc33.tokenvault.ui.miuix.AppActionButton
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
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

    // Room 首帧是异步的。以前这里是 `loaded ?: return`，于是这一页在读到数据之前
    // 什么都不画——连顶栏和返回箭头都没有，看起来像点进去之后白屏卡住了。
    // 外壳必须无条件画出来，只在内容区放"加载中"。
    val initial = loaded
    if (initial == null) {
        SettingsSubPage(titleRes = Res.string.probe_keywords, onBack = onBack) {
            item { LoadingState() }
        }
    } else {
        KeywordsEditor(initial = initial, viewModel = viewModel, onBack = onBack)
    }
}

/**
 * 读到数据之后的编辑区。
 *
 * 单独一个函数是为了让 [initial] 非空：输入框的初值只在首次组合取一次，
 * 所以它必须在"已经有真实值"之后才建立（与阈值页同一个理由）。
 */
@Composable
private fun KeywordsEditor(
    initial: List<String>,
    viewModel: ClientKeywordsViewModel,
    onBack: () -> Unit,
) {
    // 可编辑副本。初值取已存列表（可能为空 = 用默认）。
    var items by remember(initial) { mutableStateOf(initial.toList()) }
    val input = rememberAppTextFieldState("")
    val tokens = LocalAppTokens.current
    val deleteDesc = stringResource(Res.string.probe_keywords_delete)

    // 退出等的是"落库成功"事件，而不是点保存的那一刻：先退再写会让写失败发生在用户
    // 已经离开之后，而关键词表还是旧的——这一页跟阈值页共用同一条规矩。
    LaunchedEffect(viewModel) {
        viewModel.saved.collect { onBack() }
    }

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
                                maxLines = 1,
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
            // 两个动作等重，用按钮而不是行入口：行入口是一条 56dp 的 preference 行，
            // 并排放两条时第一条先按整行宽度量走，第二条（保存）直接被挤出屏幕外，
            // 于是这一页只看得见「添加」、关键词永远存不下来。
            // 同一个坑的成因与出处见 AppActionButton 的注释、ImportScreen 的按钮行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
            ) {
                AppActionButton(
                    text = stringResource(Res.string.probe_keywords_add),
                    onClick = {
                        val kw = input.text.trim()
                        if (kw.isNotEmpty() && kw !in items) {
                            items = items + kw
                            input.clear()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                AppActionButton(
                    text = stringResource(Res.string.probe_thresholds_save),
                    onClick = { viewModel.save(items) },
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
            }
        }
    }
}

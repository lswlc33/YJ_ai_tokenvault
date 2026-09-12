package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.import_confirm
import tokenvault.shared.generated.resources.import_counts
import tokenvault.shared.generated.resources.import_from_clipboard
import tokenvault.shared.generated.resources.import_issue_account_duplicate
import tokenvault.shared.generated.resources.import_issue_account_incomplete
import tokenvault.shared.generated.resources.import_issue_bad_endpoint
import tokenvault.shared.generated.resources.import_issue_balance_credential
import tokenvault.shared.generated.resources.import_issue_client_profile
import tokenvault.shared.generated.resources.import_issue_insecure_endpoint
import tokenvault.shared.generated.resources.import_issue_missing_name
import tokenvault.shared.generated.resources.import_issue_model_name
import tokenvault.shared.generated.resources.import_issue_protocol_added
import tokenvault.shared.generated.resources.import_no_preview
import tokenvault.shared.generated.resources.import_parse
import tokenvault.shared.generated.resources.import_parse_errors
import tokenvault.shared.generated.resources.import_paste_hint
import tokenvault.shared.generated.resources.import_paste_label
import tokenvault.shared.generated.resources.import_section_paste
import tokenvault.shared.generated.resources.import_section_preview
import tokenvault.shared.generated.resources.import_selected
import tokenvault.shared.generated.resources.import_skipped
import tokenvault.shared.generated.resources.import_title
import com.lc33.tokenvault.importer.ImportIssue
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/** 预览里的一条待导入供应商。 */
data class ImportPreview(
    val name: String,
    val host: String,
    val keyCount: Int,
    val modelCount: Int,
    val accountCount: Int,
    val protocols: List<String>,
    /**
     * 机器可读的问题枚举。**不是已经本地化的句子**（红线 19）：ViewModel 读不到资源，
     * 文案由 [issueMessageOf] 统一给（红线 17）。
     */
    val issues: List<ImportIssue> = emptyList(),
    val selected: Boolean = true,
)

/**
 * cURL 导入（计划.md §11、§13.4）。
 *
 * 三步：粘贴 → 预览 → 确认写入。**预览这一步不能省**：URL、鉴权头、body 都是真实
 * 调用的一部分，让用户在写库前看一眼提取结果比事后清理错误供应商便宜得多。
 *
 * 有问题的条目不阻止导入，只标出来——解析器的把握不该变成用户的阻碍。
 */
@Composable
fun ImportScreen(
    previews: List<ImportPreview>,
    parseErrors: Int,
    importing: Boolean,
    onBack: () -> Unit,
    onParse: (String) -> Unit,
    onToggle: (Int) -> Unit,
    onConfirm: () -> Unit,
    readClipboard: () -> String?,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val textFieldState = rememberAppTextFieldState()

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.import_title),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                // 粘贴那一格是多行输入，键盘弹起来会盖住「解析」按钮（同 ProviderEditorScreen）
                .imePadding()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item { SectionTitle(text = stringResource(Res.string.import_section_paste)) }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(
                        state = textFieldState,
                        label = stringResource(Res.string.import_paste_label),
                        singleLine = false,
                        supportingText = stringResource(Res.string.import_paste_hint),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
                        AppActionRow(
                            text = stringResource(Res.string.import_from_clipboard),
                            onClick = {
                                val text = readClipboard()
                                if (text != null) {
                                    textFieldState.setText(text)
                                    onParse(text)
                                }
                            },
                        )
                        AppActionRow(
                            text = stringResource(Res.string.import_parse),
                            onClick = { onParse(textFieldState.text) },
                        )
                    }
                }
            }

            if (parseErrors > 0) {
                item {
                    AppText(
                        text = pluralStringResource(Res.plurals.import_parse_errors, parseErrors, parseErrors),
                        style = AppTextStyle.Footnote,
                        color = LocalStatusPalette.current.error,
                        modifier = Modifier.padding(
                            horizontal = tokens.screenPadding,
                            vertical = tokens.itemSpacing,
                        ),
                    )
                }
            }

            if (previews.isEmpty()) {
                item {
                    AppText(
                        text = stringResource(Res.string.import_no_preview),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(
                            horizontal = tokens.screenPadding,
                            vertical = tokens.itemSpacing,
                        ),
                    )
                }
                return@LazyColumn
            }

            item { SectionTitle(text = stringResource(Res.string.import_section_preview)) }
            items(previews.size) { index -> PreviewCard(previews[index]) { onToggle(index) } }
            item {
                AppActionRow(
                    text = pluralStringResource(
                        Res.plurals.import_confirm,
                        previews.count { it.selected },
                        previews.count { it.selected },
                    ),
                    enabled = !importing && previews.any { it.selected },
                    onClick = onConfirm,
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                )
            }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
}

/** 导入问题的文案（红线 17：同一状态只有一套文案；红线 19：文案在资源里）。 */
@Composable
fun issueMessageOf(issue: ImportIssue): String = stringResource(
    when (issue) {
        ImportIssue.MISSING_NAME -> Res.string.import_issue_missing_name
        ImportIssue.BAD_ENDPOINT -> Res.string.import_issue_bad_endpoint
        ImportIssue.INSECURE_ENDPOINT -> Res.string.import_issue_insecure_endpoint
        ImportIssue.MODEL_NAME_REVIEW -> Res.string.import_issue_model_name
        ImportIssue.MODEL_PROTOCOL_ADDED -> Res.string.import_issue_protocol_added
        ImportIssue.BALANCE_MISSING_CREDENTIAL -> Res.string.import_issue_balance_credential
        ImportIssue.CLIENT_PROFILE_UNKNOWN -> Res.string.import_issue_client_profile
        ImportIssue.ACCOUNT_INCOMPLETE -> Res.string.import_issue_account_incomplete
        ImportIssue.ACCOUNT_DUPLICATE -> Res.string.import_issue_account_duplicate
    },
)

@Composable
private fun PreviewCard(preview: ImportPreview, onToggle: () -> Unit) {
    val tokens = LocalAppTokens.current
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = 4.dp),
        onClick = onToggle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                text = preview.name,
                style = AppTextStyle.Body,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            AppChip(
                text = stringResource(
                    if (preview.selected) Res.string.import_selected else Res.string.import_skipped,
                ),
            )
        }
        AppText(
            text = preview.host,
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            fontFamily = tokens.monoFontFamily,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            preview.protocols.forEach { AppChip(text = it) }
        }
        AppText(
            text = stringResource(
                Res.string.import_counts,
                preview.keyCount,
                preview.modelCount,
                preview.accountCount,
            ),
            style = AppTextStyle.Footnote,
            color = appSecondaryTextColor,
            modifier = Modifier.padding(top = 4.dp),
        )
        preview.issues.forEach { issue ->
            AppText(
                text = issueMessageOf(issue),
                style = AppTextStyle.Footnote,
                color = LocalStatusPalette.current.warn,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

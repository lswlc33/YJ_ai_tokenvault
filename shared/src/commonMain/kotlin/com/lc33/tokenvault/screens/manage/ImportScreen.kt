package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.import_action
import tokenvault.shared.generated.resources.import_duplicate_body
import tokenvault.shared.generated.resources.import_duplicate_continue
import tokenvault.shared.generated.resources.import_duplicate_title
import tokenvault.shared.generated.resources.import_error_missing_key
import tokenvault.shared.generated.resources.import_error_multiple_commands
import tokenvault.shared.generated.resources.import_error_no_command
import tokenvault.shared.generated.resources.import_from_clipboard
import tokenvault.shared.generated.resources.import_models_none
import tokenvault.shared.generated.resources.import_parse
import tokenvault.shared.generated.resources.import_paste_hint
import tokenvault.shared.generated.resources.import_paste_label
import tokenvault.shared.generated.resources.import_probe_models
import tokenvault.shared.generated.resources.import_probe_models_summary
import tokenvault.shared.generated.resources.import_result_api_key
import tokenvault.shared.generated.resources.import_result_base_url
import tokenvault.shared.generated.resources.import_result_models
import tokenvault.shared.generated.resources.import_result_protocol
import tokenvault.shared.generated.resources.import_section_paste
import tokenvault.shared.generated.resources.import_section_preview
import tokenvault.shared.generated.resources.import_title
import com.lc33.tokenvault.ui.miuix.AppActionButton
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppChip
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/** 识别成功后展示的只读结果，不包含任何明文密钥。 */
data class CurlImportPreview(
    val baseUrl: String,
    val protocols: List<String>,
    val maskedKey: String,
    val models: List<String>,
)

/** 识别失败的三类原因；文案由页面统一映射。 */
enum class CurlImportError {
    NoCommand,
    MultipleCommands,
    MissingKey,
}

@Composable
fun ImportScreen(
    preview: CurlImportPreview?,
    error: CurlImportError?,
    importing: Boolean,
    duplicatePrompt: Boolean,
    probeModels: Boolean,
    onBack: () -> Unit,
    onParse: (String) -> Unit,
    onConfirm: () -> Unit,
    onConfirmDuplicate: () -> Unit,
    onDismissDuplicate: () -> Unit,
    onProbeModelsChange: (Boolean) -> Unit,
    readClipboard: () -> String?,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val textFieldState = rememberAppTextFieldState()
    val errorText = error?.let { importErrorText(it) }

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
                actions = {
                    AppIconButton(
                        icon = AppIcon.Ok,
                        contentDescription = stringResource(Res.string.import_action),
                        onClick = onConfirm,
                        enabled = preview != null && !importing,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.sectionSpacing),
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
                        errorText = errorText,
                    )
                    // 两个动作并排、没有主次之外的层级差别，用按钮而不是行入口：
                    // 行入口并排会有一个被挤出屏幕外（第二个行的右内边距直接越界）。
                    Row(horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing)) {
                        AppActionButton(
                            text = stringResource(Res.string.import_from_clipboard),
                            onClick = { readClipboard()?.let { textFieldState.setText(it) } },
                            modifier = Modifier.weight(1f),
                        )
                        AppActionButton(
                            text = stringResource(Res.string.import_parse),
                            onClick = { onParse(textFieldState.text) },
                            modifier = Modifier.weight(1f),
                            primary = true,
                        )
                    }
                }
            }

            preview?.let { result ->
                item { SectionTitle(text = stringResource(Res.string.import_section_preview)) }
                item {
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                    ) {
                        ResultField(
                            label = stringResource(Res.string.import_result_base_url),
                            value = result.baseUrl,
                        )
                        ResultField(
                            label = stringResource(Res.string.import_result_protocol),
                            value = result.protocols.map { protocolLabel(it) }.joinToString("  "),
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
                        ResultField(
                            label = stringResource(Res.string.import_result_api_key),
                            value = result.maskedKey,
                            mono = true,
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
                        AppText(
                            text = stringResource(Res.string.import_result_models),
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                            modifier = Modifier.padding(top = tokens.itemSpacing),
                        )
                        if (result.models.isEmpty()) {
                            AppText(
                                text = stringResource(Res.string.import_models_none),
                                style = AppTextStyle.Body,
                                color = appSecondaryTextColor,
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                result.models.take(4).forEach { AppChip(text = it) }
                            }
                        }
                    }
                }
            }

            // 模型自动同步开关：导入后是否让这把 Key 自动拉取模型列表。
            // 放在预览区下方——用户刚看到解析出了哪些模型，紧接着决定要不要自动同步，
            // 认知连贯；放在预览卡片里面会打破卡片的只读语义。
            preview?.let {
                item {
                    AppPreferenceGroup(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                        inset = false,
                    ) {
                        AppSwitchRow(
                            title = stringResource(Res.string.import_probe_models),
                            summary = stringResource(Res.string.import_probe_models_summary),
                            checked = probeModels,
                            onCheckedChange = onProbeModelsChange,
                        )
                    }
                }
            }

            // 滑到底的呼吸空间：内容画到窗口底部（透出玻璃底栏），不垫就会贴边。
            item { Spacer(modifier = Modifier.height(tokens.itemSpacing)) }
        }
    }

    AppDialog(
        show = duplicatePrompt,
        onDismissRequest = onDismissDuplicate,
        title = stringResource(Res.string.import_duplicate_title),
        summary = stringResource(Res.string.import_duplicate_body),
        confirmText = null,
    ) {
        AppActionRow(
            text = stringResource(Res.string.import_duplicate_continue),
            onClick = onConfirmDuplicate,
            modifier = Modifier.fillMaxWidth(),
        )
        AppActionRow(
            text = stringResource(Res.string.dialog_cancel),
            onClick = onDismissDuplicate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ResultField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val tokens = LocalAppTokens.current
    AppText(
        text = label,
        style = AppTextStyle.Footnote,
        color = appSecondaryTextColor,
        modifier = modifier,
    )
    AppText(
        text = value,
        style = AppTextStyle.Body,
        fontFamily = if (mono) tokens.monoFontFamily else null,
    )
}

@Composable
private fun importErrorText(error: CurlImportError): String = when (error) {
    CurlImportError.NoCommand -> stringResource(Res.string.import_error_no_command)
    CurlImportError.MultipleCommands -> stringResource(Res.string.import_error_multiple_commands)
    CurlImportError.MissingKey -> stringResource(Res.string.import_error_missing_key)
}

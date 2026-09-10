package com.lc33.tokenvault.screens.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.editor_advanced_collapse
import tokenvault.shared.generated.resources.editor_advanced_expand
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.editor_section_basic
import tokenvault.shared.generated.resources.profile_editor_body_patch
import tokenvault.shared.generated.resources.profile_editor_body_patch_hint
import tokenvault.shared.generated.resources.profile_editor_curl_dropped
import tokenvault.shared.generated.resources.profile_editor_curl_failed
import tokenvault.shared.generated.resources.profile_editor_delete
import tokenvault.shared.generated.resources.profile_editor_delete_summary
import tokenvault.shared.generated.resources.profile_editor_delete_title
import tokenvault.shared.generated.resources.profile_editor_from_curl
import tokenvault.shared.generated.resources.profile_editor_from_curl_hint
import tokenvault.shared.generated.resources.profile_editor_headers
import tokenvault.shared.generated.resources.profile_editor_headers_hint
import tokenvault.shared.generated.resources.profile_editor_name
import tokenvault.shared.generated.resources.profile_editor_new_title
import tokenvault.shared.generated.resources.profile_editor_parse
import tokenvault.shared.generated.resources.profile_editor_protocols
import tokenvault.shared.generated.resources.profile_editor_protocols_hint
import tokenvault.shared.generated.resources.profile_editor_save_empty_name
import tokenvault.shared.generated.resources.profile_editor_save_empty_ua
import tokenvault.shared.generated.resources.profile_editor_title
import tokenvault.shared.generated.resources.profile_editor_user_agent
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.importer.CurlParser
import com.lc33.tokenvault.screens.model.ProfileEditorDraft
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppFilterChip
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextFieldState
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette

/**
 * 客户端伪装预设的编辑页（计划.md §8.2）。
 *
 * 一个预设的四个部分：名字（给人看的）、User-Agent（过闸的核心，M0.5 实测只换 UA 就够）、
 * 特征头（UA 不够时再叠）、bodyPatch（叠到极简探测 body 上）。协议 chips 是排序提示不是
 * 硬过滤（§8.2：中转站的闸只看请求头不看路由）。
 *
 * 文本框的内容在保存时才解析交出去（与供应商编辑页同一个理由）：headers 是**多行文本**，
 * 每行 `名称: 值`，保存时由 [parseHeaderLines] 解析成有序对。cURL 导入只是"填表"的快捷方式，
 * 解析结果直接写进这几个框，用户还能继续改。
 *
 * [initial] 为 null 表示新建；非 null 表示编辑已有预设（内置或自定义）。内置可编辑不可删。
 */
@Composable
fun ProfileEditorScreen(
    initial: ClientProfile?,
    onBack: () -> Unit,
    onSave: (ProfileEditorDraft) -> Unit,
    onDelete: () -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val isNew = initial == null
    val isBuiltin = initial?.builtinKey != null

    val name = rememberAppTextFieldState(initial?.name ?: "")
    val userAgent = rememberAppTextFieldState(initial?.userAgent ?: "")
    val headersText = rememberAppTextFieldState((initial?.headers ?: emptyList()).toHeaderLines())
    val bodyPatch = rememberAppTextFieldState(initial?.bodyPatch?.takeUnless { it == "{}" } ?: "")

    var protocols by remember { mutableStateOf(initial?.protocols ?: emptySet()) }
    var nameError by remember { mutableStateOf(false) }
    var uaError by remember { mutableStateOf(false) }

    // cURL 导入：折叠起来，新建时默认展开
    var showCurl by remember { mutableStateOf(isNew) }
    val curlText = rememberAppTextFieldState("")
    var droppedHeaders by remember { mutableStateOf<List<String>>(emptyList()) }
    var curlFailed by remember { mutableStateOf(false) }

    var showDelete by remember { mutableStateOf(false) }

    val currentProtocols by rememberUpdatedState(protocols)

    fun submit() {
        val n = name.text.trim()
        val ua = userAgent.text.trim()
        nameError = n.isEmpty()
        uaError = ua.isEmpty()
        if (nameError || uaError) return
        onSave(
            ProfileEditorDraft(
                name = n,
                userAgent = ua,
                headers = parseHeaderLines(headersText.text),
                bodyPatch = bodyPatch.text.trim().ifBlank { "{}" },
                protocols = currentProtocols,
            ),
        )
    }

    fun applyCurl() {
        val result = CurlParser.parse(curlText.text)
        val hasContent = result.userAgent != null || result.headers.isNotEmpty() ||
            result.bodyPatch != "{}"
        if (!hasContent) {
            curlFailed = true
            droppedHeaders = emptyList()
            return
        }
        curlFailed = false
        result.userAgent?.let { userAgent.setText(it) }
        if (result.headers.isNotEmpty()) headersText.setText(result.headers.toHeaderLines())
        if (result.bodyPatch != "{}") bodyPatch.setText(result.bodyPatch)
        droppedHeaders = result.droppedHeaders
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (isNew) Res.string.profile_editor_new_title else Res.string.profile_editor_title,
                ),
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
                        contentDescription = stringResource(Res.string.editor_save),
                        onClick = ::submit,
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
        ) {
            item {
                CurlImportCard(
                    expanded = showCurl,
                    curlText = curlText,
                    droppedHeaders = droppedHeaders,
                    failed = curlFailed,
                    onToggle = { showCurl = !showCurl },
                    onParse = ::applyCurl,
                )
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_basic)) }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(
                        state = name,
                        label = stringResource(Res.string.profile_editor_name),
                        errorText = if (nameError) stringResource(Res.string.profile_editor_save_empty_name) else null,
                    )
                    AppTextField(
                        state = userAgent,
                        label = stringResource(Res.string.profile_editor_user_agent),
                        errorText = if (uaError) stringResource(Res.string.profile_editor_save_empty_ua) else null,
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.profile_editor_headers)) }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                ) {
                    AppTextField(
                        state = headersText,
                        label = stringResource(Res.string.profile_editor_headers),
                        singleLine = false,
                        supportingText = stringResource(Res.string.profile_editor_headers_hint),
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.profile_editor_protocols)) }
            item { ProtocolChips(protocols) { protocols = it } }
            item {
                AppText(
                    text = stringResource(Res.string.profile_editor_protocols_hint),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(horizontal = tokens.screenPadding),
                )
            }

            item { AdvancedSection(bodyPatch) }

            if (!isBuiltin) {
                item {
                    AppActionRow(
                        text = stringResource(Res.string.profile_editor_delete),
                        onClick = { showDelete = true },
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }

    AppDialog(
        show = showDelete,
        onDismissRequest = { showDelete = false },
        title = stringResource(Res.string.profile_editor_delete_title),
        summary = stringResource(Res.string.profile_editor_delete_summary),
        confirmText = stringResource(Res.string.profile_editor_delete),
        onConfirm = {
            showDelete = false
            onDelete()
        },
    )
}

/** cURL 粘贴区。折叠起来（一个按钮），展开后是粘贴框 + 解析按钮 + 剔除说明。 */
@Composable
private fun CurlImportCard(
    expanded: Boolean,
    curlText: AppTextFieldState,
    droppedHeaders: List<String>,
    failed: Boolean,
    onToggle: () -> Unit,
    onParse: () -> Unit,
) {
    val tokens = LocalAppTokens.current
    val palette = LocalStatusPalette.current
    Column {
        AppActionRow(
            text = stringResource(Res.string.profile_editor_from_curl),
            onClick = onToggle,
            modifier = Modifier.padding(horizontal = tokens.screenPadding),
        )
        if (!expanded) return@Column
        Column(
            modifier = Modifier.padding(
                horizontal = tokens.screenPadding,
                vertical = tokens.itemSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            AppTextField(
                state = curlText,
                label = stringResource(Res.string.profile_editor_from_curl),
                singleLine = false,
                supportingText = stringResource(Res.string.profile_editor_from_curl_hint),
            )
            if (failed) {
                AppText(
                    text = stringResource(Res.string.profile_editor_curl_failed),
                    style = AppTextStyle.Footnote,
                    color = palette.error,
                )
            }
            if (droppedHeaders.isNotEmpty()) {
                AppText(
                    text = stringResource(
                        Res.string.profile_editor_curl_dropped,
                        droppedHeaders.joinToString(", "),
                    ),
                    style = AppTextStyle.Footnote,
                    color = palette.warn,
                )
            }
            AppActionRow(
                text = stringResource(Res.string.profile_editor_parse),
                onClick = onParse,
            )
        }
    }
}

@Composable
private fun ProtocolChips(selected: Set<Protocol>, onChange: (Set<Protocol>) -> Unit) {
    val tokens = LocalAppTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = tokens.screenPadding, vertical = tokens.itemSpacing),
        horizontalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
    ) {
        Protocol.entries.forEach { protocol ->
            val isSelected = protocol in selected
            AppFilterChip(
                text = protocol.name,
                selected = isSelected,
                onClick = {
                    onChange(if (isSelected) selected - protocol else selected + protocol)
                },
            )
        }
    }
}

@Composable
private fun AdvancedSection(bodyPatch: AppTextFieldState) {
    val tokens = LocalAppTokens.current
    var expanded by remember { mutableStateOf(false) }
    Column {
        AppActionRow(
            text = stringResource(
                if (expanded) Res.string.editor_advanced_collapse else Res.string.editor_advanced_expand,
            ),
            onClick = { expanded = !expanded },
            modifier = Modifier.padding(horizontal = tokens.screenPadding),
        )
        if (!expanded) return@Column
        Column(
            modifier = Modifier.padding(
                horizontal = tokens.screenPadding,
                vertical = tokens.itemSpacing,
            ),
        ) {
            AppTextField(
                state = bodyPatch,
                label = stringResource(Res.string.profile_editor_body_patch),
                singleLine = false,
                supportingText = stringResource(Res.string.profile_editor_body_patch_hint),
            )
        }
    }
}

/** 多行文本 → 有序头对。容错：不认识的行走空（UI 上表现为"这行没解析进去"），不抛。 */
private fun parseHeaderLines(text: String): List<Pair<String, String>> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null
            else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .filter { (k, _) -> k.isNotEmpty() }
        .toList()

private fun List<Pair<String, String>>.toHeaderLines(): String =
    joinToString("\n") { (k, v) -> "$k: $v" }

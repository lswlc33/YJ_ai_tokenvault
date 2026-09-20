package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import com.lc33.tokenvault.platform.PlatformBackHandler
import com.lc33.tokenvault.screens.model.ProviderDraft
import com.lc33.tokenvault.ui.common.ProviderColorSwatch
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIconDropdownRow
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppSwitchRow
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.SectionTitle
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens
import com.lc33.tokenvault.ui.theme.LocalStatusPalette
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.editor_url_err_scheme
import tokenvault.shared.generated.resources.editor_color
import tokenvault.shared.generated.resources.editor_group
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_error_missing_name
import tokenvault.shared.generated.resources.editor_row_missing
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_pinned
import tokenvault.shared.generated.resources.editor_check_website
import tokenvault.shared.generated.resources.editor_check_website_summary
import tokenvault.shared.generated.resources.provider_colors
import tokenvault.shared.generated.resources.editor_section_basic
import tokenvault.shared.generated.resources.editor_section_look
import tokenvault.shared.generated.resources.editor_discard_confirm
import tokenvault.shared.generated.resources.editor_discard_summary
import tokenvault.shared.generated.resources.editor_discard_title
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.editor_website
import tokenvault.shared.generated.resources.provider_editor_title

/** 供应商设置页：只编辑合集信息；请求与探测配置在 Key 设置页。 */
@Composable
fun ProviderEditorScreen(
    draft: ProviderDraft,
    groupNames: List<String>,
    nameMissing: Boolean,
    loadFailed: Boolean = false,
    /** 这一趟保存还在落库：顶栏对勾置灰，防止连点两下插入两家同名供应商（VM 里同源闸）。 */
    saving: Boolean = false,
    onChange: (ProviderDraft) -> Unit,
    onBack: () -> Unit,
    onSave: (ProviderDraft) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    var showDiscard by remember { mutableStateOf(false) }
    // 颜色名与调色板按**下标**对齐，两边数量由架构测试比对（provider_colors ↔
    // PROVIDER_COLOR_COUNT）：少一项的表现是"选第 8 个颜色得到第 1 个"。
    val colorNames = stringArrayResource(Res.array.provider_colors).toList()

    val name = rememberAppTextFieldState(draft.name)
    val note = rememberAppTextFieldState(draft.note)
    val website = rememberAppTextFieldState(draft.website)
    // 格式被拦过一次才显示错误：一进页面就飘红是在指责用户还没做的事。
    var websiteRejected by remember { mutableStateOf(false) }
    val currentDraft by rememberUpdatedState(draft)
    val initialDraft = remember { draft }
    val dirty = name.text != draft.name || note.text != draft.note || website.text != draft.website ||
        draft != initialDraft

    fun submit() {
        val site = website.text.trim()
        // 官网是可选栏，但填了就得是个能直接打开的地址：它会被「检查官网连通性」和
        // openExternalUrl 原样使用，`www.deepseek.com` 这种少了 scheme 的写法丢给浏览器
        // 会成一次搜索，用户看到的却是"这个软件打不开官网"。与其到时候莫名，保存前拦下。
        if (site.isNotEmpty() && !isHttpUrl(site)) {
            websiteRejected = true
            return
        }
        websiteRejected = false
        onSave(
            currentDraft.copy(
                name = name.text.trim(),
                note = note.text.trim(),
                website = site,
            ),
        )
    }

    PlatformBackHandler(enabled = dirty && !saving) { showDiscard = true }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.provider_editor_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = { if (dirty) showDiscard = true else onBack() },
                    )
                },
                actions = {
                    AppIconButton(
                        icon = AppIcon.Ok,
                        contentDescription = stringResource(Res.string.editor_save),
                        onClick = ::submit,
                        enabled = !saving,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState)
                // 这一页以前漏了它：edge-to-edge 之后 manifest 的 adjustResize 不再生效，
                // 没有 imePadding 就是键盘直接盖住正在输入的那一行（Key/Profile/导入三页都有）。
                .imePadding(),
            contentPadding = padding,
        ) {
            // 读不到那一行要挂在页面上，不能只飘一条 toast：这一页的长相与"新建一家"
            // 完全一样，而保存此刻是被挡住的——只有 toast 的话用户会反复按那枚对勾。
            if (loadFailed) {
                item {
                    AppText(
                        text = stringResource(Res.string.editor_row_missing),
                        style = AppTextStyle.Footnote,
                        color = LocalStatusPalette.current.error,
                        modifier = Modifier.padding(
                            start = tokens.screenPadding,
                            end = tokens.screenPadding,
                            top = tokens.itemSpacing,
                        ),
                    )
                }
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
                    // 名称必填：空名称的供应商在列表里只剩色块与域名，认不出是谁。
                    AppTextField(
                        state = name,
                        label = stringResource(Res.string.editor_name),
                        errorText = if (nameMissing && name.text.isBlank()) {
                            stringResource(Res.string.editor_error_missing_name)
                        } else {
                            null
                        },
                    )
                    AppTextField(state = note, label = stringResource(Res.string.editor_note))
                    // 沿用请求地址那一条 scheme 文案（红线 17：同一种状态全应用一套说法），
                    // 只是这一栏可以留空，所以空值不报错。
                    AppTextField(
                        state = website,
                        label = stringResource(Res.string.editor_website),
                        errorText = if (websiteRejected && website.text.isNotBlank() && !isHttpUrl(website.text.trim())) {
                            stringResource(Res.string.editor_url_err_scheme)
                        } else {
                            null
                        },
                    )
                }
            }
            // 「允许检查官网连通性」单独成块，紧跟官网地址：它说的就是上面那一条地址
            // （要不要去 ping 它），放进外观那一组会读成"这一家长什么样"。
            item {
                AppPreferenceGroup(
                    modifier = Modifier.padding(vertical = tokens.itemSpacing),
                ) {
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_check_website),
                        summary = stringResource(Res.string.editor_check_website_summary),
                        checked = draft.checkWebsite,
                        onCheckedChange = { onChange(draft.copy(checkWebsite = it)) },
                    )
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_look)) }
            item {
                // 分组 / 颜色 / 置顶同一组：三件事都是"这一家在列表里长什么样"，
                // 拆成三块会让外观这一节比基础信息还长。
                AppPreferenceGroup {
                    AppDropdownRow(
                        title = stringResource(Res.string.editor_group),
                        items = groupNames,
                        selectedIndex = draft.groupIndex,
                        onSelect = { onChange(draft.copy(groupIndex = it)) },
                    )
                    AppIconDropdownRow(
                        title = stringResource(Res.string.editor_color),
                        items = colorNames,
                        selectedIndex = draft.colorIndex,
                        onSelect = { onChange(draft.copy(colorIndex = it)) },
                        itemLeading = { index, cellModifier ->
                            ProviderColorSwatch(
                                index = index,
                                modifier = cellModifier,
                                size = 22.dp,
                            )
                        },
                    )
                    AppSwitchRow(
                        title = stringResource(Res.string.editor_pinned),
                        checked = draft.pinned,
                        onCheckedChange = { onChange(draft.copy(pinned = it)) },
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(tokens.sectionSpacing)) }
        }
    }
    AppDialog(
        show = showDiscard,
        onDismissRequest = { showDiscard = false },
        title = stringResource(Res.string.editor_discard_title),
        summary = stringResource(Res.string.editor_discard_summary),
        confirmText = stringResource(Res.string.editor_discard_confirm),
        // 「放弃修改」不可撤销，退路要写在按钮上，而不是让用户猜要点空白处才能留下。
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            showDiscard = false
            onBack()
        },
    )
}

/**
 * 官网的粗校验：只认 http / https 绝对地址（大小写不限）。
 *
 * 刻意"粗"：这一栏不参与端点推导，只要能被浏览器直接打开就算合格，所以不做域名解析、
 * 不查 TLD——那些规则会误伤 `http://192.168.1.7:3000` 这种自建面板地址。
 */
private fun isHttpUrl(raw: String): Boolean =
    raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)

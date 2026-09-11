package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lc33.tokenvault.platform.PlatformBackHandler
import com.lc33.tokenvault.screens.model.ProviderDraft
import com.lc33.tokenvault.ui.common.ColorSwatchRow
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppIcon
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
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.editor_color
import tokenvault.shared.generated.resources.editor_group
import tokenvault.shared.generated.resources.editor_name
import tokenvault.shared.generated.resources.editor_note
import tokenvault.shared.generated.resources.editor_pinned
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
    onChange: (ProviderDraft) -> Unit,
    onBack: () -> Unit,
    onSave: (ProviderDraft) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    var showDiscard by remember { mutableStateOf(false) }

    val name = rememberAppTextFieldState(draft.name)
    val note = rememberAppTextFieldState(draft.note)
    val website = rememberAppTextFieldState(draft.website)
    val currentDraft by rememberUpdatedState(draft)
    val dirty = name.text != draft.name || note.text != draft.note || website.text != draft.website

    fun submit() = onSave(
        currentDraft.copy(
            name = name.text.trim(),
            note = note.text.trim(),
            website = website.text.trim(),
        ),
    )

    PlatformBackHandler(enabled = dirty) { showDiscard = true }

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
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
        ) {
            item { SectionTitle(text = stringResource(Res.string.editor_section_basic)) }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
                ) {
                    AppTextField(state = name, label = stringResource(Res.string.editor_name))
                    AppTextField(state = note, label = stringResource(Res.string.editor_note))
                    AppTextField(state = website, label = stringResource(Res.string.editor_website))
                }
            }

            item { SectionTitle(text = stringResource(Res.string.editor_section_look)) }
            item {
                AppPreferenceGroup {
                    AppDropdownRow(
                        title = stringResource(Res.string.editor_group),
                        items = groupNames,
                        selectedIndex = draft.groupIndex,
                        onSelect = { onChange(draft.copy(groupIndex = it)) },
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                ) {
                    AppText(
                        text = stringResource(Res.string.editor_color),
                        style = AppTextStyle.Secondary,
                        modifier = Modifier.padding(bottom = tokens.itemSpacing),
                    )
                    ColorSwatchRow(
                        selectedIndex = draft.colorIndex,
                        onSelect = { onChange(draft.copy(colorIndex = it)) },
                    )
                }
            }
            item {
                AppPreferenceGroup {
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
        onConfirm = {
            showDiscard = false
            onBack()
        },
    )
}

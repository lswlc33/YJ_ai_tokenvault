package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.group_all
import tokenvault.shared.generated.resources.groups_add
import tokenvault.shared.generated.resources.groups_add_title
import tokenvault.shared.generated.resources.groups_count
import tokenvault.shared.generated.resources.groups_delete
import tokenvault.shared.generated.resources.groups_delete_body
import tokenvault.shared.generated.resources.groups_delete_note
import tokenvault.shared.generated.resources.groups_delete_title
import tokenvault.shared.generated.resources.groups_empty_desc
import tokenvault.shared.generated.resources.groups_empty_title
import tokenvault.shared.generated.resources.groups_name_label
import tokenvault.shared.generated.resources.groups_rename
import tokenvault.shared.generated.resources.groups_rename_title
import tokenvault.shared.generated.resources.groups_section_manage
import tokenvault.shared.generated.resources.groups_section_providers
import tokenvault.shared.generated.resources.groups_sort_hint
import tokenvault.shared.generated.resources.groups_sort_providers
import tokenvault.shared.generated.resources.groups_title
import tokenvault.shared.generated.resources.key_sort_down
import tokenvault.shared.generated.resources.key_sort_up
import tokenvault.shared.generated.resources.manage_batch_ungrouped
import tokenvault.shared.generated.resources.manage_empty_providers_desc
import tokenvault.shared.generated.resources.manage_empty_providers_title
import com.lc33.tokenvault.screens.model.UiGroup
import com.lc33.tokenvault.screens.model.UiProviderRow
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppActionRow
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppDropdownRow
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppPreferenceGroup
import com.lc33.tokenvault.ui.miuix.AppScaffold
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

/**
 * 编辑供应商列表：分组维护、供应商分组和手动排序。
 *
 * 「全部」是不可编辑、不可删除、不可拖动的伪分组。删除真实分组只把供应商落回
 * 未分组，不删除供应商。
 */
@Composable
fun GroupsScreen(
    groups: List<UiGroup>,
    providers: List<UiProviderRow>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onSetProviderGroup: (Long, Long?) -> Unit,
    onReorderProviders: (List<Long>) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val realGroups = groups.filter { it.id != null }
    val allLabel = stringResource(Res.string.group_all)
    val ungrouped = stringResource(Res.string.manage_batch_ungrouped)
    val groupChoices = listOf(UiGroup(id = null, name = ungrouped, providerCount = 0)) + realGroups

    var editing by remember { mutableStateOf<UiGroup?>(null) }
    var pendingDelete by remember { mutableStateOf<UiGroup?>(null) }
    var sorting by remember { mutableStateOf(false) }
    var ordered by remember(providers) { mutableStateOf(providers.sortedBy { it.sortOrder }) }
    val newGroup = UiGroup(id = null, name = "", providerCount = 0)

    fun leaveSorting() {
        sorting = false
        ordered = providers.sortedBy { it.sortOrder }
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (index !in ordered.indices || target !in ordered.indices) return
        val next = ordered.toMutableList()
        val current = next[index]
        next[index] = next[target]
        next[target] = current
        ordered = next
    }

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(Res.string.groups_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(Res.string.back_cd),
                        onClick = { if (sorting) leaveSorting() else onBack() },
                    )
                },
                actions = {
                    if (sorting) {
                        AppIconButton(
                            icon = AppIcon.Ok,
                            contentDescription = stringResource(Res.string.editor_save),
                            onClick = {
                                onReorderProviders(ordered.map { it.id })
                                leaveSorting()
                            },
                        )
                    } else {
                        AppIconButton(
                            icon = AppIcon.Sort,
                            contentDescription = stringResource(Res.string.groups_sort_providers),
                            onClick = { sorting = true; ordered = providers.sortedBy { it.sortOrder } },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            AppFab(
                icon = AppIcon.Add,
                contentDescription = stringResource(Res.string.groups_add),
                onClick = { editing = newGroup },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .appTopBarScroll(scrollState),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(tokens.itemSpacing),
        ) {
            item { SectionTitle(text = stringResource(Res.string.groups_section_manage)) }
            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(text = allLabel, style = AppTextStyle.Body, modifier = Modifier.weight(1f))
                        AppText(
                            text = pluralStringResource(
                                Res.plurals.groups_count,
                                providers.size,
                                providers.size,
                            ),
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                        )
                    }
                }
            }
            if (realGroups.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(Res.string.groups_empty_title),
                        description = stringResource(Res.string.groups_empty_desc),
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            } else {
                items(realGroups.size) { index ->
                    val group = realGroups[index]
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(
                                text = group.name,
                                style = AppTextStyle.Body,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            AppText(
                                text = pluralStringResource(
                                    Res.plurals.groups_count,
                                    group.providerCount,
                                    group.providerCount,
                                ),
                                style = AppTextStyle.Footnote,
                                color = appSecondaryTextColor,
                            )
                            AppIconButton(
                                icon = AppIcon.Edit,
                                contentDescription = stringResource(Res.string.groups_rename),
                                onClick = { editing = group },
                            )
                            AppIconButton(
                                icon = AppIcon.Delete,
                                contentDescription = stringResource(Res.string.groups_delete),
                                onClick = { pendingDelete = group },
                            )
                        }
                    }
                }
            }
            item {
                AppActionRow(
                    text = stringResource(Res.string.groups_add),
                    onClick = { editing = newGroup },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = tokens.screenPadding),
                )
            }

            item { SectionTitle(text = stringResource(Res.string.groups_section_providers)) }
            if (providers.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(Res.string.manage_empty_providers_title),
                        description = stringResource(Res.string.manage_empty_providers_desc),
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
            } else if (sorting) {
                item {
                    AppText(
                        text = stringResource(Res.string.groups_sort_hint),
                        style = AppTextStyle.Footnote,
                        color = appSecondaryTextColor,
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    )
                }
                items(ordered.size) { index ->
                    val provider = ordered[index]
                    AppCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = tokens.screenPadding),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(
                                text = provider.name,
                                style = AppTextStyle.Body,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            AppIconButton(
                                icon = AppIcon.Back,
                                contentDescription = stringResource(Res.string.key_sort_up),
                                onClick = { move(index, -1) },
                                enabled = index > 0,
                            )
                            AppIconButton(
                                icon = AppIcon.Forward,
                                contentDescription = stringResource(Res.string.key_sort_down),
                                onClick = { move(index, 1) },
                                enabled = index < ordered.lastIndex,
                            )
                        }
                    }
                }
            } else {
                item {
                    AppPreferenceGroup(
                        modifier = Modifier.padding(horizontal = tokens.screenPadding),
                    ) {
                        providers.forEach { provider ->
                            val selected = groupChoices.indexOfFirst { it.id == provider.groupId }
                                .coerceAtLeast(0)
                            AppDropdownRow(
                                title = provider.name,
                                items = groupChoices.map { it.name },
                                selectedIndex = selected,
                                onSelect = { index -> onSetProviderGroup(provider.id, groupChoices[index].id) },
                            )
                        }
                    }
                }
            }

            item {
                AppText(
                    text = stringResource(Res.string.groups_delete_note),
                    style = AppTextStyle.Footnote,
                    color = appSecondaryTextColor,
                    modifier = Modifier.padding(
                        horizontal = tokens.screenPadding,
                        vertical = tokens.itemSpacing,
                    ),
                )
            }
            item { Spacer(modifier = Modifier.height(tokens.fabListBottomSpace)) }
        }
    }

    val target = editing
    if (target != null) {
        key(target) {
            NameDialog(
                title = stringResource(
                    if (target.id == null) Res.string.groups_add_title else Res.string.groups_rename_title,
                ),
                initial = target.name,
                onDismiss = { editing = null },
                onConfirm = { name ->
                    editing = null
                    val id = target.id
                    if (id == null) onAdd(name) else onRename(id, name)
                },
            )
        }
    }

    AppDialog(
        show = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
        title = stringResource(Res.string.groups_delete_title),
        summary = stringResource(Res.string.groups_delete_body),
        confirmText = stringResource(Res.string.groups_delete),
        onConfirm = {
            pendingDelete?.id?.let(onDelete)
            pendingDelete = null
        },
    )
}

/** 新建 / 重命名共用的取名弹层。空名字不许提交。 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val name = rememberAppTextFieldState(initial)
    AppDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = title,
        confirmText = stringResource(Res.string.editor_save),
        onConfirm = {
            val text = name.text.trim()
            if (text.isNotEmpty()) onConfirm(text)
        },
    ) {
        AppTextField(state = name, label = stringResource(Res.string.groups_name_label))
    }
}

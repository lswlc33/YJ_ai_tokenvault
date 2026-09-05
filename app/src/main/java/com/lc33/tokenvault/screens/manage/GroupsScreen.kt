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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.UiGroup
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppDialog
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextField
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTextFieldState
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 分组管理（计划.md §13.4）。
 *
 * 「全部」不出现在这里：它是筛选条上的伪分组，不入库，也就没有"重命名全部"这种操作。
 *
 * 删除分组**不删供应商**，只是把它们的 `groupId` 清空落回「全部」——这一点必须写在
 * 确认文案里，否则"删分组"看起来像"删掉这一组供应商"。
 *
 * 新建与重命名共用一个弹层：两者的差别只有标题与初值，而分成两个的表现是
 * 同一个输入框的行为在两处慢慢长歪。
 */
@Composable
fun GroupsScreen(
    groups: List<UiGroup>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val real = groups.filter { it.id != null }

    // null = 不显示；id 为 null 的那一项表示"新建"
    var editing by remember { mutableStateOf<UiGroup?>(null) }
    var pendingDelete by remember { mutableStateOf<UiGroup?>(null) }
    val newGroup = UiGroup(id = null, name = "", providerCount = 0)

    AppScaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.groups_title),
                scrollState = scrollState,
                navigationIcon = {
                    AppIconButton(
                        icon = AppIcon.Back,
                        contentDescription = stringResource(R.string.back_cd),
                        onClick = onBack,
                    )
                },
            )
        },
        floatingActionButton = {
            AppFab(
                icon = AppIcon.Add,
                contentDescription = stringResource(R.string.groups_add),
                onClick = { editing = newGroup },
            )
        },
    ) { padding ->
        if (real.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.groups_empty_title),
                description = stringResource(R.string.groups_empty_desc),
                actionText = stringResource(R.string.groups_add),
                onAction = { editing = newGroup },
                modifier = Modifier.padding(padding),
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
            items(real.size) { index ->
                val group = real[index]
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
                                R.plurals.groups_count,
                                group.providerCount,
                                group.providerCount,
                            ),
                            style = AppTextStyle.Footnote,
                            color = appSecondaryTextColor,
                        )
                        AppIconButton(
                            icon = AppIcon.Edit,
                            contentDescription = stringResource(R.string.groups_rename),
                            onClick = { editing = group },
                        )
                        AppIconButton(
                            icon = AppIcon.Delete,
                            contentDescription = stringResource(R.string.groups_delete),
                            onClick = { pendingDelete = group },
                        )
                    }
                }
            }
            item {
                AppText(
                    text = stringResource(R.string.groups_delete_note),
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
        // key(target) 让重命名不同分组时输入框重新取初值：不加的话 remember 会保留
        // 上一个分组的名字，于是"改 B 组"打开时框里是 A 组的名字
        key(target) {
            NameDialog(
                title = stringResource(
                    if (target.id == null) R.string.groups_add_title else R.string.groups_rename_title,
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
        title = stringResource(R.string.groups_delete_title),
        summary = stringResource(R.string.groups_delete_body),
        confirmText = stringResource(R.string.groups_delete),
        onConfirm = {
            pendingDelete?.id?.let(onDelete)
            pendingDelete = null
        },
    )
}

/** 新建 / 重命名共用的取名弹层。空名字不许提交——一个没有名字的分组在筛选条上是个空 chip。 */
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
        confirmText = stringResource(R.string.editor_save),
        onConfirm = {
            val text = name.text.trim()
            if (text.isNotEmpty()) onConfirm(text)
        },
    ) {
        AppTextField(state = name, label = stringResource(R.string.groups_name_label))
    }
}

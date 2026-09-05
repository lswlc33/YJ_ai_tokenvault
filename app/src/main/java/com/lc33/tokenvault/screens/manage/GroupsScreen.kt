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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.lc33.tokenvault.R
import com.lc33.tokenvault.screens.model.UiGroup
import com.lc33.tokenvault.ui.common.EmptyState
import com.lc33.tokenvault.ui.miuix.AppCard
import com.lc33.tokenvault.ui.miuix.AppFab
import com.lc33.tokenvault.ui.miuix.AppIcon
import com.lc33.tokenvault.ui.miuix.AppIconButton
import com.lc33.tokenvault.ui.miuix.AppScaffold
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.AppTopBar
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import com.lc33.tokenvault.ui.miuix.appTopBarScroll
import com.lc33.tokenvault.ui.miuix.rememberAppTopBarScrollState
import com.lc33.tokenvault.ui.theme.LocalAppTokens

/**
 * 分组管理（计划.md §13.4）。
 *
 * 「全部」不出现在这里：它是筛选条上的伪分组，不入库，也就没有"重命名全部"这种操作。
 *
 * 删除分组**不删供应商**，只是把它们的 `groupId` 清空落回「全部」——这一点必须写在
 * 确认文案里，否则"删分组"看起来像"删掉这一组供应商"。
 */
@Composable
fun GroupsScreen(
    groups: List<UiGroup>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRename: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    val real = groups.filter { it.id != null }

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
                onClick = onAdd,
            )
        },
    ) { padding ->
        if (real.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.groups_empty_title),
                description = stringResource(R.string.groups_empty_desc),
                actionText = stringResource(R.string.groups_add),
                onAction = onAdd,
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
                            onClick = { group.id?.let(onRename) },
                        )
                        AppIconButton(
                            icon = AppIcon.Delete,
                            contentDescription = stringResource(R.string.groups_delete),
                            onClick = { group.id?.let(onDelete) },
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
}

package com.lc33.tokenvault.screens.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.rotate
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import tokenvault.shared.generated.resources.Res
import tokenvault.shared.generated.resources.back_cd
import tokenvault.shared.generated.resources.dialog_cancel
import tokenvault.shared.generated.resources.editor_save
import tokenvault.shared.generated.resources.editor_error_missing_name
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
 *
 * 排序模式让分组与供应商**同时**进入排序：两侧各有一对上下移按钮，顶栏的保存
 * 一次性提交两个顺序；退出排序模式（返回或不保存）两边都不落库。
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
    onReorderGroups: (List<Long>) -> Unit,
    onReorderProviders: (List<Long>) -> Unit,
) {
    val scrollState = rememberAppTopBarScrollState()
    val tokens = LocalAppTokens.current
    // 图标按钮的最小可点尺寸：MIUIX 的 IconButton 只保证 40dp，而改名/删除/排序都是这页的
    // 主动作，统一垫到 minTouchTarget（48dp）。分组行的行高也按同一档垫（见下面「全部」那一行），
    // 否则会出现"按钮 48、行 40"两种高度混排。
    val iconButtonTouchHeight = Modifier.heightIn(min = tokens.minTouchTarget)
    val realGroups = groups.filter { it.id != null }
    val allLabel = stringResource(Res.string.group_all)
    val ungrouped = stringResource(Res.string.manage_batch_ungrouped)
    val groupChoices = listOf(UiGroup(id = null, name = ungrouped, providerCount = 0)) + realGroups

    var editing by remember { mutableStateOf<UiGroup?>(null) }
    var pendingDelete by remember { mutableStateOf<UiGroup?>(null) }
    var sorting by remember { mutableStateOf(false) }
    var ordered by remember(providers) { mutableStateOf(providers.sortedBy { it.sortOrder }) }
    // 分组列表本身已按 sortOrder 排好（DAO 查询保证），排序态只在内存里换位置，保存才落库。
    var orderedGroups by remember(groups) { mutableStateOf(realGroups) }
    val newGroup = UiGroup(id = null, name = "", providerCount = 0)

    fun leaveSorting() {
        sorting = false
        ordered = providers.sortedBy { it.sortOrder }
        orderedGroups = realGroups
    }

    /** 把 [index] 处的元素与相邻一格互换；越界返回 null 表示不动。 */
    fun <T> moved(list: List<T>, index: Int, delta: Int): List<T>? {
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return null
        val next = list.toMutableList()
        val current = next[index]
        next[index] = next[target]
        next[target] = current
        return next
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
                                onReorderGroups(orderedGroups.mapNotNull { it.id })
                                onReorderProviders(ordered.map { it.id })
                                leaveSorting()
                            },
                        )
                    } else {
                        AppIconButton(
                            icon = AppIcon.Sort,
                            contentDescription = stringResource(Res.string.groups_sort_providers),
                            onClick = {
                                sorting = true
                                ordered = providers.sortedBy { it.sortOrder }
                                orderedGroups = realGroups
                            },
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
                    // 「全部」这一行右侧没有按钮，不垫一行最小高就会比下面的自定义分组矮一截。
                    // 垫的是 minTouchTarget（48dp）而不是 MIUIX IconButton 自带的 40dp 最小高：
                    // 下面每行的图标按钮也一并垫到 48dp（见 iconButtonTouchHeight），
                    // 40dp 是库给的地板、48dp 才是这项目认定的可点下限。
                    Row(
                        modifier = Modifier.heightIn(min = tokens.minTouchTarget),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
            if (sorting) {
                // 排序模式下分组行只留上下移：改名与删除弹窗会把排序状态打断，
                // 「全部」按规格固定不可排序（见 none.md 编辑供应商列表）。
                items(orderedGroups.size) { index ->
                    val group = orderedGroups[index]
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
                            SortMoveButtons(
                                index = index,
                                lastIndex = orderedGroups.lastIndex,
                                onMove = { i, delta ->
                                    moved(orderedGroups, i, delta)?.let { orderedGroups = it }
                                },
                            )
                        }
                    }
                }
            } else if (realGroups.isEmpty()) {
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
                                modifier = iconButtonTouchHeight,
                            )
                            AppIconButton(
                                icon = AppIcon.Delete,
                                contentDescription = stringResource(Res.string.groups_delete),
                                onClick = { pendingDelete = group },
                                modifier = iconButtonTouchHeight,
                            )
                        }
                    }
                }
            }
            if (!sorting) {
                item {
                    // 「新建分组」是一个入口行，必须包在 group 里：裸行没有容器背景与圆角，
                    // 和上面那些分组卡片（都是 AppCard）摆在一起不像同一种东西。
                    // 排序模式下整条隐藏：中途新增会让未保存的排序状态被列表刷新冲掉。
                    AppPreferenceGroup(inset = true) {
                        AppActionRow(
                            text = stringResource(Res.string.groups_add),
                            onClick = { editing = newGroup },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
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
                            SortMoveButtons(
                                index = index,
                                lastIndex = ordered.lastIndex,
                                onMove = { i, delta ->
                                    moved(ordered, i, delta)?.let { ordered = it }
                                },
                            )
                        }
                    }
                }
            } else {
                item {
                    // AppPreferenceGroup 自己已经加了一次 screenPadding，这里不能再传，
                    // 否则左右各叠一次、比同页的 AppCard 宽出 32dp。
                    AppPreferenceGroup {
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
        // 破坏性动作必须给一个看得见的退路（AppDialog 的契约）：只剩「删除」的弹层里，
        // 用户唯一的退出方式是点空白或按返回，那是在猜怎么逃。
        dismissText = stringResource(Res.string.dialog_cancel),
        onConfirm = {
            pendingDelete?.id?.let(onDelete)
            pendingDelete = null
        },
    )
}

/** 新建 / 重命名共用的取名弹层。空名字不许提交，并且要说清为什么没提交。 */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val name = rememberAppTextFieldState(initial)
    // 空名字点保存以前是"按了没反应"：没有反馈时用户既以为按钮坏了，也可能以为已经存上了，
    // 后者更糟——他会带着一个空分组名继续往下走。拦下提交的同时把原因写在输入框下面。
    var emptyName by remember { mutableStateOf(false) }
    AppDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = title,
        confirmText = stringResource(Res.string.editor_save),
        onConfirm = {
            val text = name.text.trim()
            if (text.isEmpty()) {
                emptyName = true
            } else {
                onConfirm(text)
            }
        },
    ) {
        AppTextField(
            state = name,
            label = stringResource(Res.string.groups_name_label),
            errorText = if (emptyName && name.text.isBlank()) {
                stringResource(Res.string.editor_error_missing_name)
            } else {
                null
            },
        )
    }
}

/**
 * 排序模式的一对上下移按钮。
 *
 * 图标统一用 Back（左箭头）旋转得到：MIUIX 图标库里没有现成的上/下箭头，
 * 而 Forward 是右向雪佛龙，读起来像「下一页」不是「下移」。左箭头顺时针转
 * 90° 指上、逆时针转 90° 指下，两个方向同源同形，一眼就是一对。
 */
@Composable
private fun SortMoveButtons(
    index: Int,
    lastIndex: Int,
    onMove: (Int, Int) -> Unit,
) {
    // 垫高给的是**正方形**而不是只垫高度：这两枚按钮带着 90° 旋转，48×40 转过去会占
    // 40×48 的视觉位置并被自己的布局框裁掉，正方形转完还是同一格。
    val tokens = LocalAppTokens.current
    val touchTarget = Modifier.defaultMinSize(
        minWidth = tokens.minTouchTarget,
        minHeight = tokens.minTouchTarget,
    )
    AppIconButton(
        icon = AppIcon.Back,
        modifier = touchTarget.then(Modifier.rotate(90f)),
        contentDescription = stringResource(Res.string.key_sort_up),
        onClick = { onMove(index, -1) },
        enabled = index > 0,
    )
    AppIconButton(
        icon = AppIcon.Back,
        modifier = touchTarget.then(Modifier.rotate(-90f)),
        contentDescription = stringResource(Res.string.key_sort_down),
        onClick = { onMove(index, 1) },
        enabled = index < lastIndex,
    )
}

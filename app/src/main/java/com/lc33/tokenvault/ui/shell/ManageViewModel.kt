package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.screens.model.ManageUiState
import com.lc33.tokenvault.screens.model.ProviderSort
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 管理页（§13.4）。**只列供应商**，密钥 / 模型 / 平台账号都在详情里。
 *
 * 三条决定：
 *
 * 1. **数据从 `Flow` 来，不做"写完手动刷新"**（红线 10）：新建一家之后列表自己更新，
 *    因为它订阅的是数据库。
 * 2. **分组筛选是纯 UI 状态**，留在 ViewModel 里而不是回数据层重查——筛一下就发一条
 *    SQL 只会让"点两下 chip"变成两次 IO，而结果和内存里过滤一模一样。
 * 3. **聚合状态由每家的密钥健康算**，所以要订阅全部密钥的 health 列。**一条订阅，不是每家
 *    一条**：按家订阅是 N+1，而且新增一家时那一整组 Flow 要重建，列表会闪一下。
 *    这一步不解密（§6.1 推论 3）：health 是明文列，锁定态也读得到。
 */
@HiltViewModel
class ManageViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val groups: GroupRepository,
    private val keys: ApiKeyRepository,
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<Long?>(null)

    /** 搜索串。与分组筛选一样是纯 UI 状态，筛一下不回数据层重查。 */
    private val query = MutableStateFlow("")

    /** 排序档。默认手动排序（`sortOrder`）。 */
    private val sort = MutableStateFlow(ProviderSort.MANUAL)

    /** 多选模式的选中集合。空集合 = 非多选态。 */
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * 「全部」那一枚 chip 的名字。
     *
     * 由界面注入而不是在这里写死：ViewModel 读不到资源，而红线 19 不允许代码里留中文。
     * 没注入之前是空串——那只发生在第一帧，界面一挂上 [setAllGroupLabel] 就补上。
     */
    private val allLabel = MutableStateFlow("")

    fun setAllGroupLabel(label: String) {
        if (allLabel.value != label) allLabel.value = label
    }

    /** 数据层的三份原始数据一次取好。分开 map 多次就要 combine 多次，那才会不同步。 */
    private data class Snapshot(
        val summaries: List<com.lc33.tokenvault.domain.model.ProviderSummary>,
        val groups: List<com.lc33.tokenvault.domain.model.Group>,
        val keys: List<com.lc33.tokenvault.domain.model.ApiKey>,
    )

    /** 五个纯 UI 控件态。和 [Snapshot] 分开 combine，避免单个 combine 塞 8 个流丢类型。 */
    private data class Controls(
        val selected: Long?,
        val label: String,
        val query: String,
        val sort: ProviderSort,
        val selection: Set<Long>,
    )

    private val snapshot = combine(
        providers.observeSummaries(),
        groups.observeGroups(),
        keys.observeAll(),
    ) { summaries, groupList, allKeys ->
        Snapshot(summaries, groupList, allKeys)
    }

    private val controls = combine(
        selectedGroupId,
        allLabel,
        query,
        sort,
        selection,
    ) { selected, label, q, s, sel ->
        Controls(selected, label, q, s, sel)
    }

    val state: StateFlow<ManageUiState> = combine(
        snapshot,
        controls,
    ) { snap, ctrl ->
        // 每家的聚合状态要它自己那几把密钥的 health。全部密钥一次订阅、在这里按 providerId
        // 分组，所以这一段不发额外的 SQL
        val healths = snap.keys.groupBy({ it.providerId }, { it.health })
        // 每家「最近探测」= 它那几把密钥 checkedAt 的最大值（§13.4「最近探测」排序档）。
        val lastProbeByProvider = snap.keys.groupBy({ it.providerId }, { it.checkedAt })
            .mapValues { (_, stamps) -> stamps.mapNotNull { it }.maxOrNull() }
        val rows = snap.summaries.map { summary ->
            summary.toRow(
                health = aggregateHealth(healths[summary.provider.id].orEmpty()),
                lastProbeAt = lastProbeByProvider[summary.provider.id],
            )
        }
        // 搜索 → 排序，都发生在内存里（红线 10：数据从 Flow 来，不回数据层重查）。
        val filtered = rows.filter { matchesQuery(it, groupNameOf(snap.groups, it.groupId), ctrl.query) }
        ManageUiState(
            groups = groupChips(ctrl.label, snap.groups, rows),
            selectedGroupId = ctrl.selected,
            providers = sortProviders(filtered, ctrl.sort),
            sort = ctrl.sort,
            selection = ctrl.selection,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ManageUiState())

    fun onSelectGroup(id: Long?) {
        selectedGroupId.value = id
    }

    fun onQueryChange(value: String) {
        if (query.value != value) query.value = value
    }

    fun onSort(value: ProviderSort) {
        if (sort.value != value) sort.value = value
    }

    // ---------------------------------------------------------------- 多选

    /** 长按进入多选并选中这一行。 */
    fun enterSelection(id: Long) {
        selection.value = setOf(id)
    }

    /** 多选态里点某一行：切换它的选中。 */
    fun toggleSelect(id: Long) {
        val current = selection.value
        selection.value = if (id in current) current - id else current + id
    }

    /** 全选当前筛选后的可见行。 */
    fun selectAll(visibleIds: List<Long>) {
        selection.value = visibleIds.toSet()
    }

    /** 退出多选。 */
    fun clearSelection() {
        selection.value = emptySet()
    }

    /** 批量删除。连带删密钥 / 账号 / 模型（外键 CASCADE），调用方已做二次确认。 */
    fun batchDelete(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { id -> runCatching { providers.delete(id) } }
            selection.value = emptySet()
        }
    }

    /** 批量改分组。走 [ProviderRepository.setGroup]（一条 SQL 更新多行）。 */
    fun batchSetGroup(ids: Set<Long>, groupId: Long?) {
        viewModelScope.launch {
            runCatching { providers.setGroup(ids.toList(), groupId) }
            selection.value = emptySet()
        }
    }

    fun onAddGroup(name: String) {
        // 同名会被唯一索引挡住。这里不预先查一遍再插：查与插之间有窗口，
        // 而唯一索引本来就是那条保证。失败就什么都不发生——列表没多一行，用户看得见
        viewModelScope.launch { runCatching { groups.add(name) } }
    }

    fun onRenameGroup(id: Long, name: String) {
        viewModelScope.launch { runCatching { groups.rename(id, name) } }
    }

    /** 删分组**不删供应商**：外键是 SET NULL，那些供应商落回「全部」。 */
    fun onDeleteGroup(id: Long) {
        viewModelScope.launch {
            groups.delete(id)
            // 正筛着这个分组时把筛选退回「全部」，否则列表会停在一个不存在的分组上、显示空
            if (selectedGroupId.value == id) selectedGroupId.value = null
        }
    }

    fun onDeleteProvider(id: Long) {
        viewModelScope.launch { providers.delete(id) }
    }

    private companion object {
        /** 转屏时别退订：退订会让列表在重建后闪一下空态。 */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

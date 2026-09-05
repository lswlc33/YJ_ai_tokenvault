package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.screens.model.ManageUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
 * 3. **聚合状态由每家的密钥健康算**，所以要订阅全部密钥的 health 列。这一步不解密
 *    （§6.1 推论 3）：health 是明文列，锁定态也读得到。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ManageViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val groups: GroupRepository,
    private val keys: ApiKeyRepository,
) : ViewModel() {

    private val selectedGroupId = MutableStateFlow<Long?>(null)

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

    val state: StateFlow<ManageUiState> = combine(
        providers.observeSummaries(),
        groups.observeGroups(),
        selectedGroupId,
        allLabel,
    ) { summaries, groupList, selected, label ->
        // 每家的聚合状态要它自己那几把密钥的 health。放在 combine 里逐家取是 N+1，
        // 所以下面用 observeAllHealth 一次订阅全部，这里只做拼装。
        Triple(summaries, groupList, selected to label)
    }.combine(healthByProvider()) { (summaries, groupList, selection), healthMap ->
        val rows = summaries.map { summary ->
            summary.toRow(health = aggregateHealth(healthMap[summary.provider.id].orEmpty()))
        }
        ManageUiState(
            groups = groupChips(selection.second, groupList, rows),
            selectedGroupId = selection.first,
            providers = rows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ManageUiState())

    /** providerId → 那家所有密钥的持久结论。只读 health 列，不碰密文。 */
    private fun healthByProvider() = providers.observeSummaries().flatMapLatest { summaries ->
        if (summaries.isEmpty()) {
            MutableStateFlow(emptyMap())
        } else {
            combine(summaries.map { s -> keys.observeByProvider(s.provider.id) }) { perProvider ->
                perProvider.flatMap { it }.groupBy({ it.providerId }, { it.health })
            }
        }
    }

    fun onSelectGroup(id: Long?) {
        selectedGroupId.value = id
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

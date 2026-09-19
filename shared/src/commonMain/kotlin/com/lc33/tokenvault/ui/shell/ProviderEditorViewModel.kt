package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.screens.model.ProviderDraft
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 供应商设置页：只编辑合集信息。请求与探测配置在 Key 设置页。 */
class ProviderEditorViewModel constructor(
    private val providers: ProviderRepository,
    private val groupRepository: GroupRepository,
    private val probeEngine: ProbeEngine,
    private val providerId: Long,
    /** 新建供应商的默认名（「供应商 N」，N 由它自己格式化）。由界面注入：VM 读不到资源。 */
    private val defaultProviderLabel: (Int) -> String = { "" },
) : ViewModel() {

    val groups: StateFlow<List<Group>> = groupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _draft = MutableStateFlow(ProviderDraft())
    val draft: StateFlow<ProviderDraft> = _draft.asStateFlow()

    /**
     * 草稿是否已就绪。**新建时也必须先等默认名算出来**再放行：
     * 页面用 `rememberAppTextFieldState(draft.name)` 取初值，而输入框状态只认第一次组合——
     * 放行早了，默认名稍后到达也进不了输入框，用户看到的就是一个空名称框。
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /** 名称必填的校验结果。true 时页面在名称框下给错误说明。 */
    private val _nameMissing = MutableStateFlow(false)
    val nameMissing: StateFlow<Boolean> = _nameMissing.asStateFlow()

    /**
     * 保存 / 读取失败。以前写库没有兜底：异常从协程里冒出去直接崩应用；
     * 成功事件 [_saved] 却在发出之后才动手，于是"已保存"可能是句假话。
     */
    private val _failed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val failed: SharedFlow<Unit> = _failed.asSharedFlow()

    /**
     * 按 id 读不到那一行。**显式报错而不是退化成"新建一家"**：以前读不到就悄悄把这一页
     * 当新建，用户在改「Agent Router」，按保存却多出一家同名供应商。
     */
    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    private var loadedProvider: com.lc33.tokenvault.domain.model.Provider? = null

    init {
        if (providerId != 0L) {
            viewModelScope.launch {
                val groupList = runCatching { groupRepository.observeGroups().first() }
                    .getOrDefault(emptyList())
                val provider = runCatching { providers.find(providerId) }
                    .onFailure { _failed.tryEmit(Unit) }
                    .getOrNull()
                loadedProvider = provider
                _loadError.value = provider == null
                provider?.let { _draft.value = it.toDraft(groupIndexOf(it.groupId, groupList)) }
                _loaded.value = true
            }
        } else {
            // 新建预填默认名：空名称的供应商在列表里只剩色块和域名，认不出是谁。
            viewModelScope.launch {
                val count = runCatching { providers.observeSummaries().first() }
                    .getOrDefault(emptyList())
                    .size
                _draft.value = ProviderDraft(name = defaultProviderLabel(count + 1))
                _loaded.value = true
            }
        }
    }

    fun onChange(next: ProviderDraft) {
        // 填上名字就把校验错误收回；为空时保留旧状态，等保存时再判断。
        if (next.name.isNotBlank()) _nameMissing.value = false
        _draft.value = next
    }

    fun onSave(draft: ProviderDraft) {
        // 名称必填：空名称不保存，只把校验结果翻给页面。
        if (draft.name.isBlank()) {
            _nameMissing.value = true
            return
        }
        // 读不到原行时不放行：走下去就是在库里凭空造一家供应商。
        if (_loadError.value) return
        _nameMissing.value = false
        viewModelScope.launch {
            // 先落库、成功才发"已保存"并退回；失败留在这一页，用户填的东西还在框里。
            val savedId = runCatching { providers.save(draft.toProvider(loadedProvider, groups.value)) }
                .getOrElse {
                    _failed.tryEmit(Unit)
                    return@launch
                }
            // 刚打开「允许检查官网连通性」的话，立刻去查一次——不然用户拨了开关、
            // 页面上却是空的，要等下一次全量刷新才看得到结果，很像没生效。
            // 关掉时不查（那正是关它的意思），已有结果保留着也不算错。
            if (draft.checkWebsite) {
                probeEngine.refreshReachability(savedId)
            }
            _saved.tryEmit(Unit)
        }
    }

    private fun groupIndexOf(groupId: Long?, groupList: List<Group>): Int {
        if (groupId == null) return 0
        val index = groupList.indexOfFirst { it.id == groupId }
        return if (index < 0) 0 else index + 1
    }
}

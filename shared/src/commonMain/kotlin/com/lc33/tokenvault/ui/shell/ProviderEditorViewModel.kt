package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
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

    private var loadedProvider: com.lc33.tokenvault.domain.model.Provider? = null

    init {
        if (providerId != 0L) {
            viewModelScope.launch {
                val groupList = groupRepository.observeGroups().first()
                val provider = providers.find(providerId)
                if (provider != null) {
                    loadedProvider = provider
                    _draft.value = provider.toDraft(groupIndexOf(provider.groupId, groupList))
                }
                _loaded.value = true
            }
        } else {
            // 新建预填默认名：空名称的供应商在列表里只剩色块和域名，认不出是谁。
            viewModelScope.launch {
                val count = providers.observeSummaries().first().size
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
        _nameMissing.value = false
        viewModelScope.launch {
            providers.save(draft.toProvider(loadedProvider, groups.value))
            _saved.tryEmit(Unit)
        }
    }

    private fun groupIndexOf(groupId: Long?, groupList: List<Group>): Int {
        if (groupId == null) return 0
        val index = groupList.indexOfFirst { it.id == groupId }
        return if (index < 0) 0 else index + 1
    }
}

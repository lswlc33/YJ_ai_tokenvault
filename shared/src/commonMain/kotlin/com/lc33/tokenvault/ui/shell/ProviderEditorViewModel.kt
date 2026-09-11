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
    private val providerId: Long?,
) : ViewModel() {

    val groups: StateFlow<List<Group>> = groupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _draft = MutableStateFlow(ProviderDraft())
    val draft: StateFlow<ProviderDraft> = _draft.asStateFlow()

    private val _loaded = MutableStateFlow(providerId == 0L)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    private var loadedProvider: com.lc33.tokenvault.domain.model.Provider? = null

    init {
        if (providerId != null && providerId != 0L) {
            viewModelScope.launch {
                val groupList = groupRepository.observeGroups().first()
                val provider = providers.find(providerId)
                if (provider != null) {
                    loadedProvider = provider
                    _draft.value = provider.toDraft(groupIndexOf(provider.groupId, groupList))
                }
                _loaded.value = true
            }
        }
    }

    fun onChange(next: ProviderDraft) {
        _draft.value = next
    }

    fun onSave(draft: ProviderDraft) {
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

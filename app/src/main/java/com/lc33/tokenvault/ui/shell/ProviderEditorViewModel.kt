package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.GroupRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl
import com.lc33.tokenvault.screens.model.ProviderDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

/**
 * 供应商编辑页。
 *
 * 三条决定：
 *
 * 1. **id 从 [SavedStateHandle] 取**，不靠界面在第一次组合时告诉它：进程被回收再回来时
 *    界面会重建，而 `SavedStateHandle` 里的路由参数还在。靠界面传的表现是"从后台回来，
 *    编辑页变成了新建页"。
 * 2. **保存时才规范化地址**（`normalizeBaseUrl`），失败就不写库并发一个事件让界面提示。
 *    编辑页那个实时预览已经把错误摊开了，所以这里只是最后一道闸——但它必须存在：
 *    预览是给人看的，人看不见也能点保存。
 * 3. **分组用下标而不是 id**（界面那个下拉的 API 就是 `selectedIndex`），所以 0 固定是
 *    「未分组」，其余按 [groups] 的顺序对齐。两边必须用同一份列表，否则会存错组。
 */
@HiltViewModel
class ProviderEditorViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val groupRepository: GroupRepository,
    private val clientProfiles: ClientProfileRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    /** 0 或缺失 = 新建。 */
    private val providerId: Long = savedState.get<Long>("id") ?: 0L

    val groups: StateFlow<List<Group>> = groupRepository.observeGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 客户端伪装预设。编辑页那个下拉的选项就是「默认（不伪装）」+ 这一份。 */
    val profiles: StateFlow<List<ClientProfile>> = clientProfiles.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _draft = MutableStateFlow(ProviderDraft())
    val draft: StateFlow<ProviderDraft> = _draft.asStateFlow()

    /** 加载完成了没有。没加载完就渲染会让文本框先显示空、再被真值顶掉，光标跟着跳。 */
    private val _loaded = MutableStateFlow(providerId == 0L)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 保存成功；界面收到就退出去。一次性事件，不进 UiState（那会重放）。 */
    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /** 地址规范化失败。界面自己决定怎么提示（它有那几条文案）。 */
    private val _urlRejected = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val urlRejected: SharedFlow<Unit> = _urlRejected.asSharedFlow()

    /** 编辑时手上那份领域对象。保存时用它带上不在 draft 里的字段（探测结果、余额快照…）。 */
    private var loadedProvider: Provider? = null

    init {
        if (providerId != 0L) {
            viewModelScope.launch {
                // 先等分组列表到位：groupIndex 是按列表下标算的，列表还空着时算出来一定是 0，
                // 表现是"这家明明分了组，编辑页却显示未分组"——而一保存就真的把分组清掉了
                val groupList = groupRepository.observeGroups().first()
                // 预设列表同样要等到位：profileIndex 也是按下标算的，列表空着时算出来是 0
                val profileList = clientProfiles.observeAll().first()
                val provider = providers.find(providerId)
                if (provider != null) {
                    loadedProvider = provider
                    _draft.value = provider.toDraft(groupIndexOf(provider.groupId, groupList), profileList)
                }
                _loaded.value = true
            }
        }
    }

    fun onChange(next: ProviderDraft) {
        _draft.value = next
    }

    /**
     * 保存。
     *
     * @param token 访问令牌明文，`null` 表示用户没碰那一格。**用完就地擦掉**——
     *   仓库刻意不擦入参（生命周期归调用方），而这里就是那个调用方。
     */
    fun onSave(draft: ProviderDraft, token: CharArray?) {
        val normalized = normalizeBaseUrl(draft.baseUrl, anthropicOverride(draft))
        if (normalized !is NormalizeResult.Ok) {
            token?.zeroize()
            _urlRejected.tryEmit(Unit)
            return
        }
        viewModelScope.launch {
            try {
                providers.save(draft.toProvider(loadedProvider, normalized, groups.value, profiles.value), token)
                _saved.tryEmit(Unit)
            } finally {
                token?.zeroize()
            }
        }
    }

    private fun anthropicOverride(draft: ProviderDraft): Map<Protocol, String> =
        if (draft.pathOverrideAnthropic.isBlank()) {
            emptyMap()
        } else {
            mapOf(Protocol.ANTHROPIC to draft.pathOverrideAnthropic)
        }

    /** 下标 0 是「未分组」，所以真分组从 1 开始。 */
    private fun groupIndexOf(groupId: Long?, groupList: List<Group>): Int {
        if (groupId == null) return 0
        val index = groupList.indexOfFirst { it.id == groupId }
        return if (index < 0) 0 else index + 1
    }

    override fun onCleared() {
        loadedProvider = null
    }
}

package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.screens.model.ProfileEditorDraft
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 客户端预设编辑页。
 *
 * 三条决定：
 *
 * 1. **id 从 Nav3 路由 Key 取**：back stack 恢复后仍是同一个 Key。id = 0 表示新建。
 * 2. **内置预设可编辑不可删**：`builtinKey` 非空的那枚，删除动作直接 no-op（DAO 那条
 *    `deleteCustom` 的 SQL 也带了 `builtinKey IS NULL`，双保险）。编辑内置时置
 *    `userEdited = true`，这样以后升 [BuiltinPresets.REV] 不会覆盖用户改过的指纹。
 * 3. **新建的自定义预设 `builtinKey` 是 null**，`sortOrder` 排在所有内置之后（`max + 1`），
 *    这样列表页"内置在前、自定义在后"的顺序稳定。
 */
class ProfileEditorViewModel constructor(
    private val profiles: ClientProfileRepository,
    private val profileId: Long,
) : ViewModel() {

    /** 正在编辑的那一枚。新建时为 null。 */
    private val _profile = MutableStateFlow<ClientProfile?>(null)
    val profile: StateFlow<ClientProfile?> = _profile.asStateFlow()

    private val _loaded = MutableStateFlow(profileId == 0L)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 保存成功，界面收到就退出去。一次性事件（不进 UiState，那会重放）。 */
    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    /**
     * 删除成功（只可能是自定义预设）。
     */
    private val _deleted = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    /**
     * 有一次保存 / 删除正在落库。
     *
     * 这两个动作都会以 [_saved] / [_deleted] 驱动页面 `back()`，所以连点两下的后果不是
     * "重复一次"而是"多退一级"：第二发事件到了时，第一发已经把这页弹走了，于是用户
     * 从这一页直接掉回更外面那层。检查与置位都在同一个同步块里，主线程上够用。
     */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /**
     * 写入失败（保存或删除没落库）。以前这两处都没有兜底：异常从协程里冒出去直接崩应用，
     * 而用户按下去看到的是"这一页还开着"，不知道到底存没存上。
     */
    private val _failed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val failed: SharedFlow<Unit> = _failed.asSharedFlow()

    /**
     * 按 id 读不到那一行。**显式报错，不退化成"新建一枚"**：以前读不到就悄悄把这一页
     * 当成新建，用户以为在改「Chrome」，按保存却多出一枚重名预设。
     */
    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    init {
        if (profileId != 0L) {
            viewModelScope.launch {
                val loaded = runCatching { profiles.findById(profileId) }
                    .onFailure { _failed.tryEmit(Unit) }
                    .getOrNull()
                _loadError.value = loaded == null
                _profile.value = loaded
                _loaded.value = true
            }
        }
    }

    /** 保存。新建走 [ClientProfileRepository.add]，编辑走 [ClientProfileRepository.update]。 */
    fun save(draft: ProfileEditorDraft) {
        // 读不到原行时不放行：让这一趟走下去就是在库里凭空造一枚预设。
        if (_loadError.value) return
        // 连点挡闸，理由同 ProviderEditorViewModel.saving：自定义预设的 `builtinKey`
        // 是 null，唯一索引管不到它，两下连点就真插进两枚同名预设。
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val existing = _profile.value
                val profile = ClientProfile(
                    id = existing?.id ?: 0L,
                    name = draft.name,
                    builtinKey = existing?.builtinKey,
                    userAgent = draft.userAgent,
                    headers = draft.headers,
                    bodyPatch = draft.bodyPatch,
                    protocols = draft.protocols,
                    // 命中后由嗅探置 true；用户手动改不该把它改回 false（那会丢掉"这枚被验证过"）
                    verified = existing?.verified ?: false,
                    builtinRev = existing?.builtinRev ?: 0,
                    // 只要用户保存过就置 true：内置条目下次升级不再覆盖（§8.2）
                    userEdited = true,
                    sortOrder = existing?.sortOrder
                        ?: ((runCatching { profiles.observeAll().first() }.getOrNull()
                            ?.maxOfOrNull { it.sortOrder } ?: 0) + 1),
                )
                // 成功才发 saved（界面据此退回）；失败留在这一页并报一条提示，不丢用户的输入。
                val result = runCatching {
                    if (existing == null) profiles.add(profile) else profiles.update(profile)
                }
                if (result.isSuccess) _saved.tryEmit(Unit) else _failed.tryEmit(Unit)
            } finally {
                _saving.value = false
            }
        }
    }

    /** 删除。内置（`builtinKey` 非空）不删。 */
    fun delete() {
        val existing = _profile.value ?: return
        if (existing.builtinKey != null) return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val result = runCatching { profiles.deleteCustom(existing.id) }
                if (result.isSuccess) _deleted.tryEmit(Unit) else _failed.tryEmit(Unit)
            } finally {
                _saving.value = false
            }
        }
    }
}

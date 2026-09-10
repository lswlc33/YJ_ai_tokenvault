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

    /** 删除成功（只可能是自定义预设）。 */
    private val _deleted = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deleted: SharedFlow<Unit> = _deleted.asSharedFlow()

    init {
        if (profileId != 0L) {
            viewModelScope.launch {
                _profile.value = profiles.findById(profileId)
                _loaded.value = true
            }
        }
    }

    /** 保存。新建走 [ClientProfileRepository.add]，编辑走 [ClientProfileRepository.update]。 */
    fun save(draft: ProfileEditorDraft) {
        viewModelScope.launch {
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
                    ?: ((profiles.observeAll().first().maxOfOrNull { it.sortOrder } ?: 0) + 1),
            )
            if (existing == null) profiles.add(profile) else profiles.update(profile)
            _saved.tryEmit(Unit)
        }
    }

    /** 删除。内置（`builtinKey` 非空）不删。 */
    fun delete() {
        val existing = _profile.value ?: return
        if (existing.builtinKey != null) return
        viewModelScope.launch {
            profiles.deleteCustom(existing.id)
            _deleted.tryEmit(Unit)
        }
    }
}

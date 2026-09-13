package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.SecretMask
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.UndoableDeletion
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.KeyDetailUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Key 展示页：查看一把 Key、它的模型与探测结果；设置入口从这里进。 */
class KeyDetailViewModel constructor(
    private val keys: ApiKeyRepository,
    private val clientProfiles: ClientProfileRepository,
    private val models: ModelRepository,
    private val probeEngine: ProbeEngine,
    private val clipboard: SecureClipboard,
    private val knownSecrets: KnownSecrets,
    private val providerId: Long,
    private val keyId: Long,
) : ViewModel() {

    data class RevealState(val keyId: Long, val text: String)

    private val mask = MutableStateFlow(SecretMask.ELLIPSIS)

    /**
     * 一次性事件。**只带语义、不带文案**：文案解析要 `stringResource`，那是 composable
     * 层的事（本层拿不到资源），所以这里发"发生了什么"，由 VaultNavHost 决定说什么。
     */
    sealed interface Event {
        /** 描述删除结果的语义。有 [undo] 时提示要带"撤销"。 */
        data class Deleted(val undo: UndoableDeletion?) : Event

        /** 明文已复制到剪贴板。 */
        data object Copied : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val _revealed = MutableStateFlow<RevealState?>(null)
    val revealed: StateFlow<RevealState?> = _revealed.asStateFlow()

    private var revealedPlain: CharArray? = null

    val state: StateFlow<KeyDetailUiState?> = combine(
        keys.observeByProvider(providerId),
        models.observeByProvider(providerId),
        clientProfiles.observeAll(),
        mask,
    ) { keyList, modelList, profileList, masked ->
        val key = keyList.firstOrNull { it.id == keyId } ?: return@combine null
        val profileName = key.settings.clientProfileId?.let { id ->
            profileList.firstOrNull { it.id == id }?.name ?: "#$id"
        }
        // 排序次序与 reorder() 用的是同一套（sortOrder 升序），这样菜单里"能不能上移"
        // 和按下之后实际的边界判断不会各说各话。
        val order = keyList.sortedBy { it.sortOrder }
        val position = order.indexOfFirst { it.id == keyId }
        KeyDetailUiState(
            key = key.toRow(masked, clientProfileName = profileName),
            models = modelList.filter { it.keyId == keyId }.map { it.toRow() },
            nowMs = nowMillis(),
            canMoveUp = position > 0,
            canMoveDown = position >= 0 && position < order.lastIndex,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { recomputeMask() }
    }

    fun probeKey() {
        probeEngine.probeKey(keyId)
    }

    fun refreshModels() {
        val providerId = state.value?.key?.providerId ?: return
        probeEngine.refreshModels(providerId, keyId)
    }

    fun probeModel(modelId: String, protocol: com.lc33.tokenvault.domain.Protocol) {
        probeEngine.probeModel(providerId, keyId, modelId, protocol)
    }

    fun reveal() {
        viewModelScope.launch {
            val plain = withContext(Dispatchers.Default) {
                runCatching { keys.reveal(keyId) }.getOrNull()
            } ?: return@launch
            revealedPlain?.zeroize()
            revealedPlain = plain
            knownSecrets.add(plain)
            _revealed.value = RevealState(keyId, plain.concatToString())
        }
    }

    fun copyRevealed(label: String) {
        val plain = revealedPlain ?: return
        clipboard.copy(label, plain, SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS)
        // 复制是"看不见的动作"：不提示的话用户不知道到底复制成功没有。
        _events.trySend(Event.Copied)
    }

    fun closeReveal() {
        revealedPlain?.zeroize()
        revealedPlain = null
        _revealed.value = null
    }

    fun moveUp() = reorder(-1)
    fun moveDown() = reorder(1)

    fun delete() {
        viewModelScope.launch {
            // 拿住撤销句柄再发事件：删除已经落库，提示消失前用户可以按"撤销"把它写回来。
            val undo = keys.delete(keyId)
            _events.trySend(Event.Deleted(undo))
        }
    }

    private fun reorder(delta: Int) {
        viewModelScope.launch {
            val current = state.value ?: return@launch
            val providerId = current.key.providerId
            val allKeys = keys.observeByProvider(providerId).first()
            val ids = allKeys.sortedBy { it.sortOrder }.map { it.id }.toMutableList()
            val index = ids.indexOf(keyId)
            val target = index + delta
            if (index < 0 || target < 0 || target >= ids.size) return@launch
            ids[index] = ids[target].also { ids[target] = keyId }
            keys.reorder(providerId, ids)
        }
    }

    private suspend fun recomputeMask() {
        val plain = runCatching { keys.reveal(keyId) }.getOrNull() ?: return
        try {
            mask.value = SecretMask.of(plain)
        } finally {
            plain.zeroize()
        }
    }

    override fun onCleared() {
        revealedPlain?.zeroize()
    }
}

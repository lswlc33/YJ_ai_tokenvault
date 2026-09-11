package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.SecretMask
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.KeyDetailUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Key 展示页：查看一把 Key、它的模型与探测结果；设置入口从这里进。 */
class KeyDetailViewModel constructor(
    private val keys: ApiKeyRepository,
    private val models: ModelRepository,
    private val probeEngine: ProbeEngine,
    private val clipboard: SecureClipboard,
    private val knownSecrets: KnownSecrets,
    private val providerId: Long,
    private val keyId: Long,
) : ViewModel() {

    data class RevealState(val keyId: Long, val text: String)

    private val mask = MutableStateFlow(SecretMask.ELLIPSIS)

    private val _deleted = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deleted: SharedFlow<Unit> = _deleted
    private val _revealed = MutableStateFlow<RevealState?>(null)
    val revealed: StateFlow<RevealState?> = _revealed.asStateFlow()

    private var revealedPlain: CharArray? = null

    val state: StateFlow<KeyDetailUiState?> = combine(
        keys.observeByProvider(providerId),
        models.observeByProvider(providerId),
        mask,
    ) { keyList, modelList, masked ->
        val key = keyList.firstOrNull { it.id == keyId } ?: return@combine null
        KeyDetailUiState(
            key = key.toRow(masked),
            models = modelList.filter { it.keyId == keyId }.map { it.toRow() },
            nowMs = nowMillis(),
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
            keys.delete(keyId)
            _deleted.tryEmit(Unit)
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

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
import com.lc33.tokenvault.engine.BalanceEngine
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
    private val balanceEngine: BalanceEngine,
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

        /** 顶栏「探测这把 Key」已发出（有效性 / 模型列表 / 余额，各自看开关）。 */
        data object Probed : Event
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

    /**
     * 顶栏刷新：探测这把 Key 的信息——密钥有效性（含可达性 L1+L2）、模型列表、余额。
     * 三件事各看自己的开关（`probe.keyValidity` / `probe.models` / `probe.balance`），
     * 关着的就不发。**不含模型可达性**：那一次会真花钱（红线 36），只走长按模型手动触发。
     *
     * 模型列表跟着这一轮走（L2 的响应会被复用，keyValidity 关掉时由收尾那一趟补上），
     * 不另外再调一次 [ProbeEngine.refreshModels]——那是同一份列表发两遍请求。
     */
    fun probeKey() {
        viewModelScope.launch { runCatching { balanceEngine.refresh(providerId, keyId) } }
        // 提示在动作发出的这一刻给（"正在探测该密钥…"）；这一轮的结果由 Shell 层统一播报。
        if (probeEngine.probeKey(keyId)) _events.trySend(Event.Probed)
    }

    /** 只拉模型列表（模型区那个刷新按钮）。结果由 Shell 层统一播报。 */
    fun refreshModels() {
        probeEngine.refreshModels(providerId, keyId)
    }

    /** 手动触发模型可达性探测。协议由引擎按 Chat → Anthropic 自己试，这里不需要知道。 */
    fun probeModel(modelId: String) {
        probeEngine.probeModel(providerId, keyId, modelId)
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

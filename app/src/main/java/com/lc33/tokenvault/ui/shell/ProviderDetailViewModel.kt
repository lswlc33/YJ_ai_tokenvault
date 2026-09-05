package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.SecretMask
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiProviderRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 供应商详情。**这是全应用唯一会解密密钥的页面**（§6.1 推论 3）。
 *
 * 两条明文路径，刻意分开：
 *
 * - **遮蔽串**（每一行都要）是解密后现算的，所以进这一页会把这家的密钥逐个解一遍。
 *   不存遮蔽串是刻意的——存了列表页就带着一段可读的密钥片段，"锁上了但截图里还看得见"
 *   就是这么来的。算完立刻擦明文，只留那一小段不敏感的产物。
 * - **完整明文**只有用户点了某一行才解（[onRevealKey]），拿到就展示或复制，
 *   关掉那一层就擦（[onCloseKeySheet]）。展示串是擦不掉的 `String`，
 *   所以显示它的那一层必须挂 `SecureScreen()`。
 *
 * 解密跑在 [Dispatchers.Default]：单次 AES-GCM 很快，但一家十几把密钥就是十几次
 * GCM 初始化，放主线程上是一次可见的卡顿。
 */
@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val clipboard: SecureClipboard,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val providerId: Long = savedState.get<Long>("id") ?: 0L

    /**
     * 遮蔽串缓存。
     *
     * 按 `updatedAt` 一起记：换过明文（`replaceSecret`）之后那一行必须重算，
     * 只按 id 缓存的表现是"换了密钥但遮蔽串还是旧的那一段"。
     */
    private data class Mask(val updatedAt: Long, val text: String)

    private val masks = MutableStateFlow<Map<Long, Mask>>(emptyMap())

    /** 当前展开的那一把明文。**全应用只在这里存一份**，关掉就擦。 */
    private var revealedPlain: CharArray? = null

    private val _revealed = MutableStateFlow<RevealState?>(null)
    val revealed: StateFlow<RevealState?> = _revealed.asStateFlow()

    /** 展开一把密钥时给界面的东西。 */
    data class RevealState(val keyId: Long, val text: String)

    val state: StateFlow<ProviderDetailUiState?> = combine(
        providers.observeProvider(providerId),
        keys.observeByProvider(providerId),
        masks,
    ) { provider, keyList, maskMap ->
        if (provider == null) {
            null
        } else {
            // 还没解出来的先给省略号；等下面那条协程算完会再发一次。给空串会让那一行
            // 看起来"这把密钥是空的"
            val rows = keyList.map { key -> key.toRow(maskMap[key.id]?.text ?: SecretMask.ELLIPSIS) }
            ProviderDetailUiState(
                provider = provider.toDetailRow(rows),
                keys = rows,
                // 模型与平台账号还没有仓库（M5 / M6）。空列表是诚实的：确实一条都没有
                models = emptyList(),
                accounts = emptyList(),
                nowMs = System.currentTimeMillis(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        // 订阅而不是提供一个 refresh()：新增、删除、换明文都会让这条 Flow 再发一次，
        // 于是遮蔽串跟着变（红线 10：从数据源知道该重算了，不靠每个写入点顺手通知）
        viewModelScope.launch {
            keys.observeByProvider(providerId).collect { list -> recomputeMasks(list) }
        }
    }

    /**
     * 补齐缺失或过期的遮蔽串。
     *
     * 只算变了的那几行：这条协程会被每次探测结果写入唤醒，全量重算等于每探测一轮就把
     * 这家所有密钥解一遍。
     *
     * 解不开的那一行给省略号。这不是"静默降级成 null"（红线 8）——那一行确实显示不出
     * 遮蔽串，用户看得见异常；而抛出去会让整页打不开，一把坏密文毁掉其余全部。
     */
    private suspend fun recomputeMasks(list: List<ApiKey>) {
        val current = masks.value
        val stale = list.filter { current[it.id]?.updatedAt != it.updatedAt }
        val liveIds = list.map { it.id }.toSet()
        if (stale.isEmpty() && current.keys == liveIds) return

        val computed = withContext(Dispatchers.Default) {
            stale.associate { key -> key.id to Mask(key.updatedAt, maskOf(key.id)) }
        }
        // 删掉的行要从缓存里清掉，否则这个 Map 会随着增删一直长
        masks.value = current.filterKeys { it in liveIds } + computed
    }

    private suspend fun maskOf(keyId: Long): String {
        val plain = runCatching { keys.reveal(keyId) }.getOrNull() ?: return SecretMask.ELLIPSIS
        return try {
            SecretMask.of(plain)
        } finally {
            plain.zeroize()
        }
    }

    /** 新增一把。[secret] 用完就地擦——仓库刻意不擦入参，这里就是那个负责擦的调用方。 */
    fun onAddKey(label: String, secret: CharArray) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { keys.add(providerId, label, secret) }
            } finally {
                secret.zeroize()
            }
        }
    }

    fun onSetDefaultKey(keyId: Long) {
        viewModelScope.launch { keys.setDefault(providerId, keyId) }
    }

    fun onDeleteKey(keyId: Long) {
        viewModelScope.launch { keys.delete(keyId) }
    }

    fun onRevealKey(keyId: Long) {
        viewModelScope.launch {
            val plain = withContext(Dispatchers.Default) {
                runCatching { keys.reveal(keyId) }.getOrNull()
            } ?: return@launch
            revealedPlain?.zeroize()
            revealedPlain = plain
            _revealed.value = RevealState(keyId, String(plain))
        }
    }

    /** 复制走的是**明文那一份**，不是展示串——两者内容相同，但只有前者能擦。 */
    fun onCopyRevealed(label: String) {
        val plain = revealedPlain ?: return
        clipboard.copy(label, plain, SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS)
    }

    fun onCloseKeySheet() {
        revealedPlain?.zeroize()
        revealedPlain = null
        _revealed.value = null
    }

    private fun Provider.toDetailRow(rows: List<UiKeyRow>): UiProviderRow = UiProviderRow(
        id = id,
        name = name,
        note = note,
        host = hostOf(apiRoot),
        protocols = protocolWireNames(),
        colorIndex = color ?: 0,
        pinned = pinned,
        groupId = groupId,
        keyCount = rows.size,
        okKeyCount = rows.count { it.health == UiHealth.Ok },
        modelCount = 0,
        accountCount = 0,
        balance = balance.toUiMoney(),
        health = aggregateOf(rows),
        staleThisRound = false,
    )

    /** 有一把可用就算可用（§5.3 末尾）；一把都没录是未探测，不是出错。 */
    private fun aggregateOf(rows: List<UiKeyRow>): UiHealth = when {
        rows.isEmpty() -> UiHealth.Unknown
        rows.any { it.health == UiHealth.Ok } -> UiHealth.Ok
        rows.any { it.health == UiHealth.Error } -> UiHealth.Error
        rows.any { it.health == UiHealth.Warn } -> UiHealth.Warn
        else -> UiHealth.Unknown
    }

    override fun onCleared() {
        revealedPlain?.zeroize()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

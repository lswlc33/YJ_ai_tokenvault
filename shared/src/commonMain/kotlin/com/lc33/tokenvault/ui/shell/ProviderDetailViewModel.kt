package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.platform.nowMillis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.SecretMask
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderAccount
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.ProviderAccountRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiProviderRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 *   所以只在那一层存活、关掉即丢引用。
 *
 * 解密跑在 [Dispatchers.Default]：单次 AES-GCM 很快，但一家十几把密钥就是十几次
 * GCM 初始化，放主线程上是一次可见的卡顿。
 */
class ProviderDetailViewModel constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val models: ModelRepository,
    private val accounts: ProviderAccountRepository,
    private val balanceEngine: BalanceEngine,
    private val probeEngine: ProbeEngine,
    private val clipboard: SecureClipboard,
    private val knownSecrets: com.lc33.tokenvault.crypto.KnownSecrets,
    private val providerId: Long,
) : ViewModel() {

    /**
     * 遮蔽串缓存。
     *
     * 按 `updatedAt` 一起记：换过明文（`replaceSecret`）之后那一行必须重算，
     * 只按 id 缓存的表现是"换了密钥但遮蔽串还是旧的那一段"。
     */
    private data class Mask(val updatedAt: Long, val text: String)

    private val masks = MutableStateFlow<Map<Long, Mask>>(emptyMap())

    /**
     * 账号用户名的遮蔽串缓存，按 `updatedAt` 记（和密钥 [masks] 同一套逻辑）。
     * 用户名也加密（红线 21），遮蔽串要解密现算；密码连遮蔽串都不给，只在展开时现算。
     */
    private val accountMasks = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** 当前展开的那一把明文。**全应用只在这里存一份**，关掉就擦。 */
    private var revealedPlain: CharArray? = null

    private val _revealed = MutableStateFlow<RevealState?>(null)
    val revealed: StateFlow<RevealState?> = _revealed.asStateFlow()

    /** 展开一把密钥时给界面的东西。 */
    data class RevealState(val keyId: Long, val text: String)

    /**
     * 当前展开的那条账号的明文（用户名 + 密码各一份 [CharArray]，红线 1）。与密钥的
     * [revealedPlain] 分开存：账号展开的是两段明文，关掉都要擦。
     */
    private var revealedAccountPlain: AccountPlain? = null

    private val _revealedAccount = MutableStateFlow<AccountRevealState?>(null)
    val revealedAccount: StateFlow<AccountRevealState?> = _revealedAccount.asStateFlow()

    /** 展开一条账号时给界面的东西。两段都可能为 null（只记了一半，§11.2）。 */
    data class AccountRevealState(
        val accountId: Long,
        val loginMethods: Set<LoginMethod> = emptySet(),
        val label: String,
        val username: String?,
        val password: String?,
    )

    private data class AccountPlain(
        val username: CharArray?,
        val password: CharArray?,
    ) {
        fun zeroize() {
            username?.zeroize()
            password?.zeroize()
        }
    }

    // 内层：四路数据流（供应商 / 密钥 / 模型 / 账号）先合成一份，外层再接两份遮蔽串缓存。
    // 六个流超过 kotlinx.coroutines combine 的 5 流类型化上限，拆成两层（§9.2 同款拆法）。
    private data class DetailData(
        val provider: Provider?,
        val keys: List<ApiKey>,
        val models: List<AiModel>,
        val accounts: List<ProviderAccount>,
    )

    val state: StateFlow<ProviderDetailUiState?> = combine(
        combine(
            providers.observeProvider(providerId),
            keys.observeByProvider(providerId),
            models.observeByProvider(providerId),
            accounts.observeByProvider(providerId),
        ) { provider, keyList, modelList, accountList ->
            DetailData(provider, keyList, modelList, accountList)
        },
        masks,
        accountMasks,
    ) { data, maskMap, accountMaskMap ->
        if (data.provider == null) {
            null
        } else {
            val provider = data.provider
            // 还没解出来的先给省略号；等下面那条协程算完会再发一次。给空串会让那一行
            // 看起来"这把密钥是空的"
            val keyRows = data.keys.map { key ->
                key.toRow(maskMap[key.id]?.text ?: SecretMask.ELLIPSIS)
            }
            val modelRows = data.models.map { it.toRow() }
            val accountRows = data.accounts.map { account ->
                account.toRow(accountMaskMap[account.id] ?: SecretMask.ELLIPSIS)
            }
            ProviderDetailUiState(
                provider = provider.toDetailRow(
                    keyRows,
                    data.keys,
                    modelCount = modelRows.filter { it.enabled }.distinctBy { it.modelId }.size,
                    accountCount = accountRows.size,
                ),
                keys = keyRows,
                models = modelRows,
                accounts = accountRows,
                modelListEnabled = provider.probe.models,
                modelReachabilityEnabled = provider.probe.modelReachability,
                nowMs = nowMillis(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        // 订阅而不是提供一个 refresh()：新增、删除、换明文都会让这条 Flow 再发一次，
        // 于是遮蔽串跟着变（红线 10：从数据源知道该重算了，不靠每个写入点顺手通知）
        viewModelScope.launch {
            keys.observeByProvider(providerId).collect { list -> recomputeMasks(list) }
        }
        viewModelScope.launch {
            accounts.observeByProvider(providerId).collect { list -> recomputeAccountMasks(list) }
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

    /** 账号用户名遮蔽串的重算，逻辑同 [recomputeMasks]。没记用户名的给空串（不是省略号）。 */
    private suspend fun recomputeAccountMasks(list: List<ProviderAccount>) {
        val liveIds = list.map { it.id }.toSet()
        if (accountMasks.value.keys == liveIds) return

        val computed = withContext(Dispatchers.Default) {
            list.associate { account -> account.id to maskOfUsername(account.id) }
        }
        accountMasks.value = computed
    }

    private suspend fun maskOfUsername(accountId: Long): String {
        val plain = runCatching { accounts.revealUsername(accountId) }.getOrNull() ?: return ""
        return try {
            SecretMask.ofUsername(plain)
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
            // 登记已知明文：这把密钥刚被用户看到，之后若它出现在探测错误 / 审计日志里，
            // 脱敏器（红线 32 第一道）要能认出它、擦掉它。
            knownSecrets.add(plain)
            _revealed.value = RevealState(keyId, plain.concatToString())
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

    /** 展开一条账号：用户名与密码各解一份（没有的那份给 null），拿到就展示，关掉就擦。 */
    fun onRevealAccount(accountId: Long) {
        viewModelScope.launch {
            val plain = withContext(Dispatchers.Default) {
                val username = runCatching { accounts.revealUsername(accountId) }.getOrNull()
                val password = runCatching { accounts.revealPassword(accountId) }.getOrNull()
                if (username == null && password == null) null
                else AccountPlain(username, password)
            }             ?: return@launch
            revealedAccountPlain?.zeroize()
            revealedAccountPlain = plain
            // 账号的用户名 / 密码也是秘密（红线 21），展开后同样登记进已知明文清单。
            plain.username?.let(knownSecrets::add)
            plain.password?.let(knownSecrets::add)
            _revealedAccount.value = AccountRevealState(
                accountId = accountId,
                loginMethods = state.value?.accounts
                    ?.firstOrNull { it.id == accountId }
                    ?.loginMethods
                    ?.mapNotNull { LoginMethod.fromWireName(it) }
                    ?.toSet()
                    .orEmpty(),
                label = state.value?.accounts?.firstOrNull { it.id == accountId }?.label.orEmpty(),
                username = plain.username?.let { it.concatToString() },
                password = plain.password?.let { it.concatToString() },
            )
        }
    }

    /** 复制账号密码明文（复制的是密码那一份；没记密码就复制用户名）。 */
    fun onCopyRevealedAccount(label: String) {
        val plain = revealedAccountPlain ?: return
        val secret = plain.password ?: plain.username ?: return
        clipboard.copy(label, secret, SecureClipboard.DEFAULT_AUTO_CLEAR_SECONDS)
    }

    fun onCloseAccountSheet() {
        revealedAccountPlain?.zeroize()
        revealedAccountPlain = null
        _revealedAccount.value = null
    }

    /** 手动添加模型。模型列表自动检测关闭时，这是唯一入口。 */
    fun onAddModel(keyId: Long, modelId: String, protocol: Protocol) {
        viewModelScope.launch {
            runCatching {
                models.add(
                    providerId = providerId,
                    keyId = keyId,
                    modelId = modelId,
                    protocol = protocol,
                    needsReview = modelId.any { it.isWhitespace() || it.isUpperCase() },
                )
            }
        }
    }

    /** 手动编辑模型。只改明文元数据，不触发网络请求。 */
    fun onUpdateModel(
        id: Long,
        modelId: String,
        protocol: Protocol,
        displayName: String?,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            val existing = models.observeByProvider(providerId).first()
                .firstOrNull { it.id == id } ?: return@launch
            models.update(
                existing.copy(
                    modelId = modelId.trim(),
                    protocol = protocol,
                    displayName = displayName?.trim()?.ifEmpty { null },
                    enabled = enabled,
                ),
            )
        }
    }

    fun onDeleteModel(id: Long) {
        viewModelScope.launch { models.delete(id) }
    }

    /** 模型可达性探测（快捷）：长按模型行手动触发。 */
    fun onProbeModel(keyId: Long, modelId: String, protocol: Protocol) {
        probeEngine.probeModel(providerId, keyId, modelId, protocol)
    }

    /** 登录方式是明文元数据，可以在展开账号时直接修改。 */
    fun onSetAccountLoginMethods(accountId: Long, methods: Set<LoginMethod>) {
        viewModelScope.launch {
            // 先落库再更新展开层，失败时不让 chip 假装已经生效。
            runCatching { accounts.setLoginMethods(accountId, methods) }
                .onSuccess {
                    _revealedAccount.value = _revealedAccount.value
                        ?.takeIf { it.accountId == accountId }
                        ?.copy(loginMethods = methods)
                }
        }
    }

    /** 详情页「查余额」。结果经 observeProvider 那条订阅流回，不用手动刷新（红线 10）。 */
    fun refreshBalance() {
        viewModelScope.launch {
            runCatching { balanceEngine.refresh(providerId) }
        }
    }

    /** 详情页「探测这一家」。只发 L1+L2（零成本，红线 36），结果流回 `probe_runs` 与明细页。 */
    fun probeProvider() {
        probeEngine.probeProvider(providerId)
    }

    /** 详情页 Key 行「单 Key 探测」。只发一次 L2（零成本），验证这一张 Key 是否有效。 */
    fun probeKey(keyId: Long) {
        probeEngine.probeKey(keyId)
    }

    /** 手动拉模型列表。null = 这家全部启用 Key；指定 id = 只拉那一张 Key。 */
    fun refreshModels(keyId: Long? = null) {
        probeEngine.refreshModels(providerId, keyId)
    }

    private fun Provider.toDetailRow(
        rows: List<UiKeyRow>,
        keyList: List<ApiKey>,
        modelCount: Int,
        accountCount: Int,
    ): UiProviderRow {
        val aggregateBalance = aggregateBalanceOf(keyList)
        return UiProviderRow(
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
            modelCount = modelCount,
            accountCount = accountCount,
            balance = aggregateBalance.toUiMoney(),
            balanceFailed = aggregateBalance?.failed == true,
            health = aggregateOf(rows),
            staleThisRound = false,
        )
    }

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
        revealedAccountPlain?.zeroize()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

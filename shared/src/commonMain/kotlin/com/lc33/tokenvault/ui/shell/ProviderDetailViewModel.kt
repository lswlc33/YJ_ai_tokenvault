package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.nowMillis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.crypto.KnownSecrets
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
import com.lc33.tokenvault.domain.repo.UndoableDeletion
import com.lc33.tokenvault.engine.BalanceEngine
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.screens.model.ProviderDetailUiState
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiModelRow
import com.lc33.tokenvault.screens.model.UiProviderRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val knownSecrets: KnownSecrets,
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
     * 一次性事件。只带语义、不带文案——文案解析在 composable 层（本层拿不到资源）。
     */
    sealed interface Event {
        /** 模型已删除；[undo] 非空时提示要带"撤销"。 */
        data class ModelDeleted(val undo: UndoableDeletion?) : Event

        /** 平台账号已删除；[undo] 非空时提示要带"撤销"。 */
        data class AccountDeleted(val undo: UndoableDeletion?) : Event

        /** 账号密码明文已复制。 */
        data object Copied : Event

        /** 模型/账号保存成功。 */
        data object ModelSaved : Event
        data object AccountSaved : Event

        /** 模型/账号**没能**写进库。没有这一条时失败是静默的：弹层收了、列表没变。 */
        data object WriteFailed : Event

        /** 一键探测已发出（官网 / 密钥 / 模型列表 / 余额）。结果本身由状态流回填。 */
        data object Probed : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /**
     * 账号用户名的遮蔽串缓存，按 `updatedAt` 记（和密钥 [masks] 同一套逻辑）。
     * 用户名也加密（红线 21），遮蔽串要解密现算；密码连遮蔽串都不给，只在展开时现算。
     */
    private val accountMasks = MutableStateFlow<Map<Long, Mask>>(emptyMap())

    /**
     * 当前展开的那条账号的明文（用户名 + 密码各一份 [CharArray]，红线 1）。与密钥的
     * [revealedPlain] 分开存：账号展开的是两段明文，关掉都要擦。
     */
    private var revealedAccountPlain: AccountPlain? = null
    private var revealJob: Job? = null
    private var revealGeneration = 0L

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
            // 按 (Key, 模型 id) 收一次：库里同一个 modelId 可以按协议各存一行（红线 18 说
            // 协议属于模型，一个模型在 chat 与 responses 上都可用就是两行），而这一页
            // **不显示协议尾巴**——两份都画出来就是"每个模型出现两遍"，那句"N 个模型"
            // 也跟着列表数、与供应商卡上的去重计数各说各话。DAO 按 sortOrder 排过，
            // 留下的是最先发现的那一行。Key 详情页保留两行，那里协议 chip 看得见。
            val modelRows = data.models.distinctBy { it.keyId to it.modelId }.map { it.toRow() }
            val accountRows = data.accounts.map { account ->
                account.toRow(accountMaskMap[account.id]?.text ?: SecretMask.ELLIPSIS)
            }
            ProviderDetailUiState(
                provider = provider.toDetailRow(
                    rows = keyRows,
                    keyList = data.keys,
                    models = data.models,
                    accountCount = accountRows.size,
                ),
                keys = keyRows,
                models = modelRows,
                accounts = accountRows,
                nowMs = nowMillis(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * 库已经读过、但这一行不在。
     *
     * [state] 为 null 其实有两种意思：还没读到，和读到了但没有这条。页面只看 null 就会
     * 把后者也当成前者，于是删掉一家供应商之后从探测明细点它的行，就永远停在"加载中"
     * （从备份恢复会把所有 id 重排，是同一个洞的另一个入口）。这一条把后者单独说出来。
     */
    val rowGone: StateFlow<Boolean> = providers.observeProvider(providerId)
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

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
        val current = accountMasks.value
        val stale = list.filter { current[it.id]?.updatedAt != it.updatedAt }
        val liveIds = list.map { it.id }.toSet()
        if (stale.isEmpty() && current.keys == liveIds) return

        val computed = withContext(Dispatchers.Default) {
            stale.associate { account ->
                account.id to Mask(account.updatedAt, maskOfUsername(account.id))
            }
        }
        accountMasks.value = current.filterKeys { it in liveIds } + computed
    }

    private suspend fun maskOfUsername(accountId: Long): String {
        val plain = runCatching { accounts.revealUsername(accountId) }.getOrNull() ?: return ""
        return try {
            SecretMask.ofUsername(plain)
        } finally {
            plain.zeroize()
        }
    }

    /**
     * 新增平台账号。凭据字段用 CharArray 进入仓库，成功后由调用方与仓库共同擦除。
     *
     * 成功 / 失败都要回事件：以前只有成功路径发（新增发、编辑不发），失败时弹层已经收了，
     * 列表却什么都没多出来——那是"点了保存没反应"。
     */
    fun onAddAccount(
        label: String,
        note: String,
        username: CharArray?,
        password: CharArray?,
        loginMethods: Set<LoginMethod>,
    ) {
        viewModelScope.launch {
            try {
                runCatching {
                    accounts.add(
                        providerId = providerId,
                        label = label,
                        username = username,
                        password = password,
                        loginUrl = null,
                        loginMethods = loginMethods,
                        note = note,
                    )
                }.onSuccess { _events.trySend(Event.AccountSaved) }.onFailure {
                    _events.trySend(Event.WriteFailed)
                }
            } finally {
                username?.zeroize()
                password?.zeroize()
            }
        }
    }

    /**
     * 编辑平台账号。空数组表示清空该凭据；null 表示用户没有改这一格、保留原值。
     * 非密码登录会显式传空数组清掉用户名和密码。
     */
    fun onUpdateAccount(
        id: Long,
        label: String,
        note: String,
        username: CharArray?,
        password: CharArray?,
        loginMethods: Set<LoginMethod>,
        usesPassword: Boolean,
    ) {
        viewModelScope.launch {
            try {
                val clear = CharArray(0)
                runCatching {
                    accounts.update(
                        id = id,
                        label = label,
                        username = if (usesPassword) username else clear,
                        password = if (usesPassword) password else clear,
                        loginUrl = null,
                        loginMethods = loginMethods,
                        note = note,
                    )
                }.onSuccess { _events.trySend(Event.AccountSaved) }.onFailure {
                    _events.trySend(Event.WriteFailed)
                }
            } finally {
                username?.zeroize()
                password?.zeroize()
            }
        }
    }

    fun onDeleteAccount(id: Long) {
        viewModelScope.launch {
            val undo = runCatching { accounts.delete(id) }.getOrNull()
            if (_revealedAccount.value?.accountId == id) onCloseAccountSheet()
            _events.trySend(Event.AccountDeleted(undo))
        }
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
            }.onSuccess { _events.trySend(Event.ModelSaved) }.onFailure {
                _events.trySend(Event.WriteFailed)
            }
        }
    }

    /**
     * 手动编辑模型。只改明文元数据，不触发网络请求。
     *
     * 保存成功也要回一条"已保存"：以前只有新增路径发，编辑那一趟弹层收了、行没变、
     * 什么提示都没有，用户分不清"没改"和"没保存上"。
     */
    fun onUpdateModel(
        id: Long,
        modelId: String,
        protocol: Protocol,
        displayName: String?,
    ) {
        viewModelScope.launch {
            val existing = runCatching { models.observeByProvider(providerId).first() }
                .getOrNull()?.firstOrNull { it.id == id }
            if (existing == null) {
                _events.trySend(Event.WriteFailed)
                return@launch
            }
            runCatching {
                models.update(
                    existing.copy(
                        modelId = modelId.trim(),
                        protocol = protocol,
                        displayName = displayName?.trim()?.ifEmpty { null },
                    ),
                )
            }.onSuccess { _events.trySend(Event.ModelSaved) }.onFailure {
                _events.trySend(Event.WriteFailed)
            }
        }
    }

    fun onDeleteModel(id: Long) {
        viewModelScope.launch {
            runCatching { models.delete(id) }
                .onSuccess { undo -> _events.trySend(Event.ModelDeleted(undo)) }
                .onFailure { _events.trySend(Event.WriteFailed) }
        }
    }

    /**
     * 展开一条账号看明文。
     *
     * 标题与登录方式**从仓库现取**而不是读 [state]：那条流在退订的瞬间是 null
     * （切后台再回来的第一帧就是），以前读到 null 就悄悄擦掉明文直接返回，
     * 表现成"点查看账号明文没反应"。
     */
    fun onRevealAccount(accountId: Long) {
        val generation = ++revealGeneration
        revealJob?.cancel()
        clearRevealedAccount()
        revealJob = viewModelScope.launch {
            val plain = withContext(Dispatchers.Default) {
                val username = runCatching { accounts.revealUsername(accountId) }.getOrNull()
                val password = runCatching { accounts.revealPassword(accountId) }.getOrNull()
                if (username == null && password == null) null else AccountPlain(username, password)
            } ?: return@launch
            if (generation != revealGeneration) {
                plain.zeroize()
                return@launch
            }
            val account = runCatching { accounts.observeByProvider(providerId).first() }
                .getOrNull()?.firstOrNull { it.id == accountId }
            if (account == null) {
                plain.zeroize()
                return@launch
            }
            revealedAccountPlain = plain
            plain.username?.let(knownSecrets::add)
            plain.password?.let(knownSecrets::add)
            _revealedAccount.value = AccountRevealState(
                accountId = accountId,
                loginMethods = account.loginMethods,
                label = account.label,
                username = plain.username?.let { it.concatToString() },
                password = plain.password?.let { it.concatToString() },
            )
        }
    }

    /** 复制指定账号的凭据，避免异步切换时复制到另一账号。 */
    fun onCopyRevealedAccount(accountId: Long, label: String) {
        if (_revealedAccount.value?.accountId != accountId) return
        val plain = revealedAccountPlain ?: return
        val secret = plain.password ?: plain.username ?: return
        clipboard.copy(label, secret)
        _events.trySend(Event.Copied)
    }

    fun onCloseAccountSheet() {
        ++revealGeneration
        revealJob?.cancel()
        revealJob = null
        clearRevealedAccount()
    }

    private fun clearRevealedAccount() {
        revealedAccountPlain?.zeroize()
        revealedAccountPlain = null
        _revealedAccount.value = null
    }

    /**
     * 详情页顶栏的「一键探测」。
     *
     * 一次把这家的**四类**探测都发出去，各自看自己的开关：
     * 官网连通性（[ProbeEngine.refreshReachability]）、密钥可达性与有效性
     * （[ProbeEngine.probeProvider]，L1+L2，零成本）、模型列表（**跟着这一轮走**，见下）、
     * 余额（[BalanceEngine.refresh]，尊重每把 Key 的余额类型与余额探测开关）。
     *
     * **不单独调 [ProbeEngine.refreshModels]**：这一轮本身就会顺手拉模型列表
     * （L2 的响应被复用，keyValidity 关掉的 Key 由收尾那一趟补上），再调一次等于同一份
     * 列表发两遍请求、还多弹一条提示。
     *
     * **只发零成本的那几类**：模型可达性（L3）要真发一次推理请求、会花钱，仍然只走
     * Key 页长按手动触发（红线 36）。
     */
    fun probeAll() {
        probeEngine.refreshReachability(providerId)
        viewModelScope.launch { runCatching { balanceEngine.refresh(providerId) } }
        // 提示在动作发出的这一刻给（"已开始探测"）；这一轮的结果由 Shell 层统一播报。
        if (probeEngine.probeProvider(providerId)) _events.trySend(Event.Probed)
    }

    /**
     * 只拉模型列表（密钥卡上那个刷新按钮）。
     *
     * 不在这里发"已刷新"：刷新是异步的，写在这是说了句还没发生的事。什么时候刷完由
     * [ProbeEngine.modelResults] 告诉 Shell。
     *
     * 返回值是"**这一发有没有真的发出去**"（引擎在跑别的轮 / 锁定态时是 false）：
     * 页面用它决定要不要说"正在刷新模型列表"，否则就是发了一条没在发生的提示。
     */
    fun refreshModels(keyId: Long? = null): Boolean = probeEngine.refreshModels(providerId, keyId)

    private fun Provider.toDetailRow(
        rows: List<UiKeyRow>,
        keyList: List<ApiKey>,
        models: List<AiModel>,
        accountCount: Int,
    ): UiProviderRow {
        val aggregateBalance = aggregateBalanceOf(keyList)
        return UiProviderRow(
            id = id,
            name = name,
            note = note,
            websiteUrl = websiteUrl,
            // 与列表页用同一个 helper：不再是这里的 firstOrNull，而是"排序第一把"，
            // 两边算同一件事就必须走同一段代码。
            host = providerHostOf(keyList),
            // 协议 = 这家所有模型的协议并集，和管理页 / 仪表盘同一个定义
            protocols = providerProtocolsOf(models),
            colorIndex = color ?: 0,
            pinned = pinned,
            groupId = groupId,
            keyCount = rows.size,
            okKeyCount = rows.count { it.health == UiHealth.Ok },
            modelCount = models.distinctBy { it.modelId }.size,
            accountCount = accountCount,
            balance = aggregateBalance.toUiMoney(),
            balanceFailed = aggregateBalance?.failed == true,
            // 与列表页同一套取值口径：整家都挂时聚合快照带着原因，部分挂时从 Key 行里捞
            // 第一个失败者的（见 UiMapping 里 ProviderSummary.toRow 的同一段注释）。
            balanceErrorReason = aggregateBalance.failureReason()
                ?: rows.firstNotNullOfOrNull { it.balanceErrorReason },
            balanceErrorHint = aggregateBalance.failureHint()
                ?: rows.firstNotNullOfOrNull { it.balanceErrorHint },
            balanceConfigured = balanceConfiguredOf(keyList),
            balanceFailedKeyCount = failedBalanceKeyCountOf(keyList),
            balanceCheckedAt = aggregateBalance?.checkedAt,
            health = aggregateOf(rows),
            // 官网延迟只在**连通**时给：失败那次拿到的耗时说明不了任何事，
            // 显示出来会被读成"通了但很慢"。
            reachabilityLatencyMs = website.latencyMs.takeIf { website.ok },
            keys = rows,
        )
    }

    /**
     * 有一把可用就算可用（§5.3 末尾）；一把都没录是未探测，不是出错。
     *
     * 走 [aggregateHealth] 而不是在这里重写一遍分支：这段逻辑三处都要用（管理页、
     * 仪表盘、这里），各写一遍的下场是"某天给某一档改了颜色，另外两处还是旧行为"。
     */
    private fun aggregateOf(rows: List<UiKeyRow>): UiHealth =
        aggregateHealth(rows.map { it.health })

    override fun onCleared() {
        revealedAccountPlain?.zeroize()
    }
}

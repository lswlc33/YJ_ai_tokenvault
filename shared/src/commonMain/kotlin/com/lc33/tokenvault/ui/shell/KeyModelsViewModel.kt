package com.lc33.tokenvault.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lc33.tokenvault.catalog.CatalogSyncState
import com.lc33.tokenvault.domain.ModelFilter
import com.lc33.tokenvault.domain.ModelFamily
import com.lc33.tokenvault.domain.ModelGroupBy
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ModelSort
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.listModels
import com.lc33.tokenvault.domain.ListedModel
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.CatalogModel
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.ModelCatalogRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.UndoableDeletion
import com.lc33.tokenvault.engine.CatalogSync
import com.lc33.tokenvault.engine.ProbeEngine
import com.lc33.tokenvault.platform.SecureClipboard
import com.lc33.tokenvault.platform.nowMillis
import com.lc33.tokenvault.screens.model.MODEL_GROUP_ROW_PAGE
import com.lc33.tokenvault.screens.model.KeyModelsUiState
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiModelCardRow
import com.lc33.tokenvault.screens.model.UiModelGroup
import com.lc33.tokenvault.screens.model.UiModelMeta
import com.lc33.tokenvault.screens.model.UiModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 整屏模型页：一把 Key 的模型列表，分组 / 排序 / 筛选 / 搜索 / 展开看能力。
 *
 * 六份来源合流成一份状态，缺一条都会画错：
 * - 模型行（`models`，按 keyId 过滤）；
 * - 目录元数据（`catalog`，按行上的 `catalogKey` 一次捞）；
 * - 厂商展示名（`vendors`，分组标题要用的那个正式名）；
 * - 界面意图（分组方式、排序、筛选、搜索词、折叠、展开）；
 * - 目录同步状态（顶栏那句进度）；
 * - 目录是否从没同步过（决定"更新目录"入口要不要摆到显眼处）。
 *
 * 搜索/分组/筛选全部**在内存里算**，不下推成 SQL：一把 Key 最多几百行，而"折叠 + 搜索词 +
 * 能力筛选"三样要拼进一条 `@Query` 就变成一段没法单测的动态 SQL 字符串。真正的规则在
 * `domain/listModels`，这里只负责喂数据。
 */
class KeyModelsViewModel constructor(
    private val keys: ApiKeyRepository,
    private val models: ModelRepository,
    private val providers: ProviderRepository,
    private val catalog: ModelCatalogRepository,
    private val catalogSync: CatalogSync,
    private val probeEngine: ProbeEngine,
    private val clipboard: SecureClipboard,
    private val providerId: Long,
    private val keyId: Long,
) : ViewModel() {

    /** 一次性事件。只带语义不带文案，文案由 `VaultNavHost` 用资源解析。 */
    sealed interface Event {
        data object Copied : Event

        /** 删除结果。带撤销句柄时提示要挂"撤销"按钮。 */
        data class Deleted(val undo: UndoableDeletion?) : Event

        /** 手动添加撞上唯一索引：同 Key 下同 id 同协议已经有了。 */
        data object Duplicate : Event

        /** 单模型探测已发出（引擎没接这一发时不会发这个事件）。 */
        data object Probed : Event

        /** 目录同步结束。null = 成功；非 null 是失败原因（英文诊断串，只进日志）。 */
        data class CatalogSynced(val failure: String?) : Event

        data object WriteFailed : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private data class Options(
        val groupBy: ModelGroupBy = ModelGroupBy.FAMILY,
        val sort: ModelSort = ModelSort.NAME_ASC,
        val filter: ModelFilter = ModelFilter.ALL,
        val query: String = "",
        val collapsed: Set<String> = emptySet(),
        /** 每组已经放出来多少行（缺省 = [MODEL_GROUP_ROW_PAGE]）。见 `UiModelGroup.hiddenRows`。 */
        val shownRows: Map<String, Int> = emptyMap(),
    )

    private val options = MutableStateFlow(Options())
    private val rows = MutableStateFlow<List<AiModel>>(emptyList())
    private val metaByKey = MutableStateFlow<Map<String, CatalogModel>>(emptyMap())
    private val vendorNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private val catalogEmpty = MutableStateFlow(true)
    private val header = MutableStateFlow(Header())

    /** 页面顶栏与"能不能编辑"都要用的那几项，来自 Key 本身。 */
    private data class Header(
        val keyLabel: String = "",
        val providerName: String = "",
        /** 可写 = 这把 Key 没开「模型列表自动获取」。开了的话下次同步就覆盖，编辑没意义。 */
        val editable: Boolean = false,
        /** 长按/按钮能不能真发一次模型可达性探测——沿用详情页那条"两档都开"的判定。 */
        val quickProbe: Boolean = false,
        /**
         * 模型那条流**已经发过第一帧**。
         *
         * 没这一位就分不开"还没读到"和"读到了、这把 Key 确实没有模型"：两者都是空列表，
         * 于是进页面的第一帧会闪一句"该密钥没有模型"，紧接着整屏列表才跳出来。
         * 放在 Header 里搭同一趟 combine，是为了不再为单独一个布尔加第六条来源流。
         */
        val rowsLoaded: Boolean = false,
    )

    init {
        viewModelScope.launch {
            keys.observeByProvider(providerId).collect { list ->
                val key = list.firstOrNull { it.id == keyId }
                val probe = key?.settings?.probe
                // editable = true = 这把 Key 没开「模型列表自动获取」
                header.value = header.value.copy(
                    keyLabel = key?.label.orEmpty(),
                    editable = probe?.models?.not() == true,
                    // 与详情页同一条判定：老库里可能只开了其中一个（v4 迁移是从
                    // modelReachability 抄过去的），引擎要两个都真才会发探测。
                    quickProbe = probe != null && probe.enabled && probe.modelReachability && probe.quickModelProbe,
                )
            }
        }
        viewModelScope.launch {
            providers.observeProvider(providerId).collect { provider ->
                header.value = header.value.copy(providerName = provider?.name.orEmpty())
            }
        }
        viewModelScope.launch {
            models.observeByProvider(providerId).collect { all ->
                val forThisKey = all.filter { it.keyId == keyId }
                rows.value = forThisKey
                // 目录那一趟捞不到只影响"能力"面板，不该把整屏模型带走：这里必须收住
                // 异常，否则它从 collect 里冒出去，ViewModel 协程没有父级接，整页没了。
                val meta = runCatching {
                    catalog.findByKeys(forThisKey.mapNotNull { it.catalogKey }.distinct())
                }.getOrDefault(emptyMap())
                metaByKey.value = meta
                header.value = header.value.copy(rowsLoaded = true)
            }
        }
        // 厂商展示名只在分组标题里用，但它要按**族键**查：族键来自模型 id 前缀，
        // 而 model_vendors.slug 是目录的外层 key。两者只在"前缀恰好等于厂商 slug"
        // 时对得上（deepseek、openai 这类）；对不上就退回首字母大写的族键，
        // 这正是 ModelFamily.displayOfKey 的兜底。
        viewModelScope.launch {
            catalog.observeVendors().collect { vendors ->
                vendorNames.value = vendors.associate { it.slug to it.name }
            }
        }
        viewModelScope.launch {
            // 目录行数为 0 = 从没同步成功过。查询一次就够：同步成功后这条会由
            // CatalogSync 的完成事件再问一次（见 syncCatalog），不必让它常驻订阅。
            catalogEmpty.value = runCatching { catalog.modelCount() == 0 }.getOrDefault(true)
        }
        viewModelScope.launch {
            // 进页面补一发按需同步：受自动更新开关与 7 天节奏管，挡下时返回 false，
            // 界面什么都不说（没说"正在更新"才不会让用户等一件没发生的事）。
            runCatching { catalogSync.syncIfNeeded(force = false) }
                .onSuccess { ran -> if (ran) _events.trySend(Event.CatalogSynced(null)) }
                .onFailure { _events.trySend(Event.CatalogSynced(it.message ?: "sync failed")) }
        }
    }

    /** 前五条来源的打包，喂给五参 `combine`：它的重载到五路为止，六路会退回 vararg 那版。 */
    private data class Sources(
        val rows: List<AiModel>,
        val meta: Map<String, CatalogModel>,
        val opts: Options,
        val header: Header,
        val vendors: Map<String, String>,
    )

    val state: StateFlow<KeyModelsUiState> = combine(
        combine(rows, metaByKey, options, header, vendorNames) { r, m, o, h, v ->
            Sources(r, m, o, h, v)
        },
        combine(catalogSync.state, catalogEmpty) { sync, empty -> sync to empty },
    ) { sources, (sync, empty) ->
        build(
            sources.rows,
            sources.meta,
            sources.opts,
            sources.header,
            sources.vendors,
            sync,
            empty,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        build(emptyList(), emptyMap(), Options(), Header(), emptyMap(), null, true),
    )

    private fun build(
        models: List<AiModel>,
        meta: Map<String, CatalogModel>,
        opts: Options,
        h: Header,
        vendors: Map<String, String>,
        sync: CatalogSyncState?,
        empty: Boolean,
    ): KeyModelsUiState {
        val listing = listModels(
            models = models,
            catalogByKey = meta,
            groupBy = opts.groupBy,
            sort = opts.sort,
            filter = opts.filter,
            query = opts.query,
            vendorNames = vendors,
        )
        return KeyModelsUiState(
            keyLabel = h.keyLabel,
            providerName = h.providerName,
            editable = h.editable,
            quickProbe = h.quickProbe,
            loading = !h.rowsLoaded,
            totalModels = listing.totalModels,
            matchedModels = listing.matchedModels,
            visibleModels = listing.visibleModels,
            groupBy = opts.groupBy,
            sort = opts.sort,
            filter = opts.filter,
            query = opts.query,
            // 只在"正在跑"与"刚失败"时给状态：Synced 是过去式，常驻一条"已更新"
            // 会在用户下次搜索时还挂在那里。
            catalogSync = sync?.takeIf {
                it is CatalogSyncState.Downloading ||
                    it is CatalogSyncState.Importing ||
                    it is CatalogSyncState.Failed
            },
            catalogNeverSynced = empty,
            nowMs = nowMillis(),
            groups = listing.groups.map { group ->
                // 一组一次只组合这么几行：整组摊在一个 LazyColumn item 里，445 个模型那把
                // Key 的 OpenAI 组有 91 行，每行还带几枚能力 chip，一帧画完就是卡的地方。
                val limit = opts.shownRows[group.key] ?: MODEL_GROUP_ROW_PAGE
                UiModelGroup(
                    key = group.key,
                    title = when (opts.groupBy) {
                        ModelGroupBy.NONE -> ""
                        ModelGroupBy.FAMILY -> ModelFamily.displayOfKey(group.key, vendors[group.key])
                    },
                    collapsed = group.key in opts.collapsed,
                    rows = group.rows.take(limit).map { row -> row.toCardRow(h.quickProbe) },
                    hiddenRows = (group.rows.size - limit).coerceAtLeast(0),
                )
            },
        )
    }

    private fun ListedModel.toCardRow(quickProbe: Boolean) = UiModelCardRow(
        id = model.id,
        modelId = model.modelId,
        protocol = model.protocol.wireName,
        source = if (model.source == ModelSource.MANUAL) UiModelSource.Manual else UiModelSource.Discovered,
        health = when (model.probeState) {
            ModelProbeState.OK -> UiHealth.Ok
            ModelProbeState.NOT_FOUND -> UiHealth.Error
            ModelProbeState.NO_ACCESS, ModelProbeState.ERROR -> UiHealth.Warn
            ModelProbeState.UNKNOWN -> UiHealth.Unknown
        },
        quickProbe = quickProbe,
        probedAt = model.probedAt,
        latencyMs = model.latencyMs,
        meta = catalog?.toUiMeta(),
    )

    // ------------------------------------------------------------------ 交互

    fun onQueryChange(text: String) {
        options.value = options.value.copy(query = text)
    }

    fun onGroupBy(index: Int) {
        options.value = options.value.copy(groupBy = ModelGroupBy.entries.getOrElse(index) { ModelGroupBy.FAMILY })
    }

    fun onSort(index: Int) {
        options.value = options.value.copy(sort = ModelSort.entries.getOrElse(index) { ModelSort.NAME_ASC })
    }

    fun onFilter(filter: ModelFilter) {
        options.value = options.value.copy(filter = filter)
    }

    fun toggleGroup(key: String) {
        val current = options.value.collapsed
        options.value = options.value.copy(collapsed = if (key in current) current - key else current + key)
    }

    /**
     * 放出一组里剩下的模型，每次多 [MODEL_GROUP_ROW_PAGE] 行，而不是一次全展开。
     *
     * 一次全展开就是这一页卡的那个原因：那一组的几十行会在同一帧里全组合出来。
     */
    fun showMoreRows(key: String) {
        val current = options.value.shownRows[key] ?: MODEL_GROUP_ROW_PAGE
        options.value = options.value.copy(
            shownRows = options.value.shownRows + (key to current + MODEL_GROUP_ROW_PAGE),
        )
    }

    /**
     * 复制模型 ID。模型名常要贴进客户端配置里，这是这一页最常用的动作。
     *
     * 不进脱敏清单：模型 id 不是秘密（密钥与账号密码才是），登记进去只会让日志里
     * 满屏 `gpt-4o` 变成遮蔽串，反而看不出问题。
     */
    fun copyModelId(label: String, modelId: String) {
        if (modelId.isBlank()) return
        clipboard.copy(label, modelId.toCharArray())
        _events.trySend(Event.Copied)
    }

    /**
     * 探测单个模型。协议由引擎按 Chat → Anthropic 自己试，这一行记的协议不参与。
     *
     * 返回 false = 引擎没接这一发（已有一发在跑、库锁着、两档开关没开）。那时不报
     * "已探测"：预告一件没发生的事，比什么都不说更糟。
     */
    fun probeModel(modelId: String): Boolean {
        val started = probeEngine.probeModel(providerId, keyId, modelId)
        if (started) _events.trySend(Event.Probed)
        return started
    }

    /** 顶栏刷新 = 重新拉这一把 Key 的模型列表。返回 false = 这一发没发出去。 */
    fun refreshModels(): Boolean = probeEngine.refreshModels(providerId, keyId)

    /** 手动拉一次 models.dev 目录；进页面时那句"从没同步过"也走这里。 */
    fun syncCatalog() {
        viewModelScope.launch {
            // 同步结束要重问一次目录行数：这是"从没同步过"那句提示消失的唯一时机。
            runCatching { catalogSync.syncNow() }
                .onSuccess { ran ->
                    if (ran) {
                        catalogEmpty.value = runCatching { catalog.modelCount() == 0 }.getOrDefault(true)
                        _events.trySend(Event.CatalogSynced(null))
                    }
                }
                .onFailure { _events.trySend(Event.CatalogSynced(it.message ?: "sync failed")) }
        }
    }

    fun addModel(modelId: String, protocol: Protocol) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // -1 = 撞上唯一索引（同一 Key 下同 id 同协议已经有了）。那不是写失败，
            // 但也不该静默：用户会以为自己加上了，列表里却没有。
            // 真抛出去的异常原来也走同一条 `getOrDefault(0L)`，于是"写不进去"被报成
            // "已存在"——两句是相反的诊断，一个让用户别再加，一个让他换个 id 再来。
            val added = withContext(Dispatchers.Default) {
                runCatching { models.add(providerId, keyId, trimmed, protocol) }
            }
            when {
                added.isFailure -> _events.trySend(Event.WriteFailed)
                added.getOrDefault(-1L) <= 0L -> _events.trySend(Event.Duplicate)
            }
        }
    }

    /** 编辑一行模型（id / 显示名 / 协议）。模型表没有密文，整行保存即可。 */
    fun updateModel(id: Long, modelId: String, protocol: Protocol, displayName: String?) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 那一行已经不在了（在别处被删了）：弹层收了、列表没变，不说一句就是"按钮坏了"。
            // 不写成 `?: return@launch also { … }`——`return` 先跳走，那个 also 根本不会执行。
            val current = rows.value.firstOrNull { it.id == id }
            if (current == null) {
                _events.trySend(Event.WriteFailed)
                return@launch
            }
            val updated = current.copy(
                modelId = trimmed,
                protocol = protocol,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
            )
            // 改了 id 就要重挂目录：旧 catalogKey 指的是改名前的那个模型，
            // 留着会让详情里显示的厂商/上下文属于另一个模型。
            val rekeyed = if (trimmed == current.modelId) {
                updated
            } else {
                val key = withContext(Dispatchers.Default) {
                    runCatching { catalog.lookup(trimmed, vendorHint = null)?.key }.getOrNull()
                }
                updated.copy(catalogKey = key)
            }
            val ok = withContext(Dispatchers.Default) {
                runCatching { models.update(rekeyed) }.isSuccess
            }
            if (!ok) _events.trySend(Event.WriteFailed)
        }
    }

    fun deleteModel(id: Long) {
        viewModelScope.launch {
            // 与详情页同一条：先拿住撤销句柄再发事件，删除已经落库，
            // 提示消失前用户可以按"撤销"把它写回来。异常必须说，不能让它从协程里冒出去。
            runCatching { models.delete(id) }
                .onSuccess { undo -> _events.trySend(Event.Deleted(undo)) }
                .onFailure { _events.trySend(Event.WriteFailed) }
        }
    }

    private companion object {
        fun CatalogModel.toUiMeta(): UiModelMeta = UiModelMeta(
            displayName = name,
            vendorName = vendorName,
            description = description,
            context = formatTokens(contextLimit),
            output = formatTokens(outputLimit),
            reasoning = reasoning,
            toolCall = toolCall,
            structuredOutput = structuredOutput,
            attachment = attachment,
            openWeights = openWeights,
            vision = inputModalities.contains("image"),
            audioIn = inputModalities.contains("audio"),
            videoIn = inputModalities.contains("video"),
            releaseDate = releaseDate,
            knowledgeCutoff = knowledgeCutoff,
            status = status,
        )

        /** `128000` → `128K`、`1000000` → `1M`。K/M 不是要翻译的词，所以留在这里格式化。 */
        fun formatTokens(value: Int?): String? {
            val v = value ?: return null
            if (v <= 0) return null
            return when {
                v >= 1_000_000 -> "${v / 1_000_000}.${((v % 1_000_000) / 100_000)}M"
                v >= 1_000 -> "${(v + 500) / 1_000}K"
                else -> v.toString()
            }
        }
    }
}

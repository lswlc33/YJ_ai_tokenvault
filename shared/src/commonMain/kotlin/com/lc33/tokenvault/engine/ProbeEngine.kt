package com.lc33.tokenvault.engine

import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.ModelRepository
import com.lc33.tokenvault.domain.repo.ProbeRun
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.endpoint.HeaderAssembler
import com.lc33.tokenvault.endpoint.ApiEndpointSet
import com.lc33.tokenvault.endpoint.mergeBodyPatch
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeRequestBuilder
import com.lc33.tokenvault.endpoint.ProbeResponse
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.probe.PlannedTask
import com.lc33.tokenvault.probe.MODEL_PROBE_PROTOCOL_ORDER
import com.lc33.tokenvault.probe.ModelListParse
import com.lc33.tokenvault.probe.ModelListParser
import com.lc33.tokenvault.probe.ModelMerger
import com.lc33.tokenvault.probe.modelProbeStateOf
import com.lc33.tokenvault.probe.ProbeBudget
import com.lc33.tokenvault.probe.Classification
import com.lc33.tokenvault.probe.ProbeClassifier
import com.lc33.tokenvault.probe.ProbeItemResult
import com.lc33.tokenvault.probe.ProbeOrchestrator
import com.lc33.tokenvault.probe.ProbePlan
import com.lc33.tokenvault.probe.ProbePlanBuilder
import com.lc33.tokenvault.probe.ProbeProgress
import com.lc33.tokenvault.probe.ProbeTask
import com.lc33.tokenvault.probe.ProbeTransport
import com.lc33.tokenvault.probe.SniffAttempt
import com.lc33.tokenvault.probe.SniffPlanBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * 探测引擎宿主（计划.md §8.5）。**不是 ViewModel**——探测要能跨页面存活，
 * 用户在仪表盘点"开始探测"后切去管理页，引擎不能跟着 ViewModel 一起死。
 *
 * 四条设计决定：
 *
 * 1. **自己的作用域**。内部 `CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeCrashGuard)`，
 *    不借 `@AppScope`：后者是应用级、永不取消，而探测要在锁定 / 手动取消时真的停。
 * 2. **逐项落库、逐项推流**。编排器 [ProbeOrchestrator] 是纯逻辑，这里把它的每一项结果
 *    写回 `api_keys` 并更新 `probe_runs`，所以中途被锁定 / 取消也不丢已完成的结果。
 * 3. **密钥明文活得最短**。L2 发请求前才 [ApiKeyRepository.reveal]，构造完鉴权头立刻
 *    [zeroize]；嗅探要重发几次，就 reveal 一次、几轮共用、最后擦掉。
 * 4. **客户端伪装是数据不是代码**（红线 22）。请求头一律经 [HeaderAssembler] 从
 *    `client_profiles` 表组装，UA / 特征头在这里没有一处硬编码。
 */
class ProbeEngine constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val models: ModelRepository,
    private val clientProfiles: ClientProfileRepository,
    private val runRepository: ProbeRunRepository,
    private val session: ProbeSession,
    private val engine: HttpEngine,
    private val audit: AuditLogRepository,
    private val settings: SettingsRepository,
    private val autoLocker: IdleLockSuspender,
    private val redactor: Redactor,
    private val knownSecrets: KnownSecrets,
    private val now: () -> Long,
    private val placeholders: Map<String, String>,
) {

    // 挂 [scopeCrashGuard]：轮次里的异常本来由 runRound 的 catch 收口，这一道是给"收口本身
    // 又抛了"（收尾写库失败）留的最后一层——没有它，一次写库失败就是杀进程。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeCrashGuard)

    /**
     * 这四个 Job 跨线程读写，必须 `@Volatile`：主线程在 [startScoped]/[cancel]/[quickProbe]
     * 里写，而 [onLock] 是被 `AutoLocker` 的后台协程调的（它跑在 `idleJob` 那条协程上）。
     * 少了这层可见性，自动锁定那一刻可能读到旧值或 null——**金库已经锁上、网络请求继续发**，
     * 或者反过来点"开始探测"没反应。`kotlin.jvm.Volatile` 在 iOS 不存在，用 kotlin.concurrent 那份。
     */
    @Volatile
    private var currentJob: Job? = null
    @Volatile
    private var modelJob: Job? = null
    @Volatile
    private var statusJob: Job? = null
    @Volatile
    private var quickModelJob: Job? = null

    /**
     * 一轮结束的通知，给界面发"探测结果"提示用。
     *
     * 为什么由引擎发而不是让每个页面自己等：[start] / [probeProvider] / [probeKey] 全是
     * 发起即返回，页面拿不到"什么时候跑完"。放在这里一处发，四处都能收到同一个结论。
     */
    data class RoundResult(
        val total: Int,
        val ok: Int,
        val fail: Int,
        val cancelled: Boolean,
    )

    /** 模型列表刷新结束的通知。discovered 是本轮拿到的模型条数。 */
    data class ModelRefreshResult(val discovered: Int, val failed: Boolean)

    private val _roundResults = MutableSharedFlow<RoundResult>(extraBufferCapacity = 8)
    val roundResults: SharedFlow<RoundResult> = _roundResults.asSharedFlow()

    private val _modelResults = MutableSharedFlow<ModelRefreshResult>(extraBufferCapacity = 8)
    val modelResults: SharedFlow<ModelRefreshResult> = _modelResults.asSharedFlow()

    private val _progress = MutableStateFlow<ProbeProgress?>(null)
    val progress: StateFlow<ProbeProgress?> = _progress.asStateFlow()

    private val _results = MutableSharedFlow<ProbeItemResult>(extraBufferCapacity = 64)
    val results: Flow<ProbeItemResult> = _results.asSharedFlow()

    /**
     * 当前（或最近）一轮结果的累计快照。
     *
     * [results] 是 `SharedFlow`，明细页打开时上一轮已经推完、拿不到。所以这里另存一份
     * 累计列表，每轮开始时清空、逐项 append——明细页订阅它就能看到"上一轮完整结果"。
     */
    private val _lastRound = MutableStateFlow<List<ProbeItemResult>>(emptyList())
    val lastRound: StateFlow<List<ProbeItemResult>> = _lastRound.asStateFlow()

    /**
     * 丢掉内存里这一轮的累计结果。数据页「清空探测结果」必须连它一起清。
     *
     * 那一发清的是库（`probe_runs` 与密钥上的探测字段），而明细页读的是这份内存快照：
     * 不一起清就会"已清空"之后进明细页还看得到全部旧行，而同一时刻总览那张卡已经变成
     * 「还没探测过」。两个屏幕对同一件事各说一套。
     */
    fun clearLastRound() {
        _lastRound.value = emptyList()
    }

    /**
     * 本轮撞过 429 的 host 记在 [engine]（HostGate）那里，不在这里再存一份。
     *
     * 原来这是一个裸 `mutableSetOf`：编排器的工作线程往里写、这里的 `trySniff` 读，
     * 而两者跑在不同的协程线程上（`Dispatchers.Default` 是多线程的），既没有可见性
     * 保证也可能丢更新。门闸本来就是"host 级节流状态"的权威存储、又已经收在一把 Mutex
     * 后面，并进去比在这里再配一把锁少一个会忘记同步的地方（红线 31 的口径：一件事
     * 只有一个权威）。
     */

    /** 一轮探测是否在跑。 */
    val running: Boolean get() = currentJob?.isActive == true

    /**
     * 开始一轮全量探测（仪表盘"开始探测"）。
     *
     * 已在跑则不重复启动（幂等）；锁定态直接不启动——探测需要 reveal 密钥，而那是
     * 要借 DEK 的（§6.1 推论 3）。返回 false 表示这次没有启动。
     */
    fun start(): Boolean = startScoped(runScope = "all") { true }

    /**
     * 仅重试上一轮的失败项与未探测项（明细页"重试失败项"，§13.4）。
     *
     * 复用 [startScoped]：把上一轮 [ProbeItemResult.outcome] 是失败 / 跳过 / 取消的
     * `taskId` 提出来当过滤条件，只重跑这些任务。
     *
     * **这一轮重跑过的项会覆盖 [lastRound] 里的旧行，没重跑的留着**（[runRoundInner]
     * 按任务的 `taskId` 决定留谁）：全部清空的话，用户点一次"重试失败项"就把上一轮的
     * 成功记录一起弄没了，明细页突然只剩三行，看不出"那三家本来是好的"。
     * 返回 false 表示没有可重试的项、或已在跑、或锁定态。
     */
    fun retryFailed(): Boolean {
        if (running) return false
        if (!session.isUnlocked) return false
        val retryIds = _lastRound.value
            .filter { it.outcome != ProbeOutcome.SUCCESS }
            .map { it.taskId }
            .toSet()
        if (retryIds.isEmpty()) return false
        return startScoped(runScope = RETRY_SCOPE) { it.id in retryIds }
    }

    /**
     * 只探测这一家（详情页「探测这一家」，§8.6 手动触发点）。
     *
     * 与 [start] 同一套编排，只是把 `providerId` 不匹配的骨架任务过滤掉——所以
     * `probeEnabled = 0`、端点规范化失败、reveal 失败的 Key 依旧被 `ProbePlanBuilder` /
     * `toTask` 挡掉，单家探测不绕过总闸。只发 L1+L2（零成本，红线 36）。
     * 返回 false 表示已在跑、或锁定态。
     */
    fun probeProvider(providerId: Long): Boolean =
        startScoped(runScope = "provider:$providerId") { it.providerId == providerId }

    /**
     * 只探测这一张 Key（详情页 Key 行「单 Key 探测」，§8.6 手动触发点）。
     *
     * 复用 [startScoped] 的二级过滤，只留 `keyId` 命中的 L2 任务。单张 Key 只发一次 L2
     * （零成本），不触发 L3（红线 36）。返回 false 表示已在跑、或锁定态。
     */
    fun probeKey(keyId: Long): Boolean =
        startScoped(runScope = "key:$keyId") { it.keyId == keyId }

    /**
     * 手动拉取模型列表。`keyId = null` 表示这一家全部启用 Key；否则只拉这一张 Key。
     *
     * 与普通探测分开跑：模型列表是 GET、零成本，但结果要进三路合并，不适合塞进
     * `probe_runs` 的进度语义。仍然尊重探测总闸与 Key 探测开关，锁定态不发。
     *
     * **每把 Key 恰好一次 GET**，且只看这把 Key 的 `probe.models`：既不看可达性也不看
     * 密钥有效性开关（那两个各铺一份任务，同一份列表会被发两三次、按协议各写一套行）。
     */
    fun refreshModels(providerId: Long, keyId: Long? = null): Boolean {
        if (modelJob?.isActive == true) return false
        if (!session.isUnlocked) return false

        modelJob = scope.launch {
            autoLocker.pauseIdleLock()
            try {
                // 结果在编排外面发：里面有好几条提前返回（没开自动获取 / 没有可用 Key），
                // 只有在唯一出口发才能保证"开始刷新"之后一定有"刷完了"。
                _modelResults.tryEmit(refreshModelsInner(providerId, keyId))
            } finally {
                autoLocker.resumeIdleLock()
            }
        }
        return true
    }

    private suspend fun refreshModelsInner(providerId: Long, keyId: Long?): ModelRefreshResult {
        val provider = providers.observeSummaries().first()
            .map { it.provider }
            .firstOrNull { it.id == providerId }
            ?: return ModelRefreshResult(discovered = 0, failed = true)

        val selectedKeys = keys.observeAll().first()
            .filter {
                it.providerId == providerId &&
                    (keyId == null || it.id == keyId) &&
                    it.settings.probe.enabled &&
                    it.settings.probe.models
            }
        // 没开自动获取：什么都没做，但这不是失败——按钮本来就是给自动获取用的。
        if (selectedKeys.isEmpty()) return ModelRefreshResult(discovered = 0, failed = false)

        val profileList = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        val clientKeywords = this.settings.observeClientKeywords().first()
        // 每把 Key 恰好一条 GET，不复用探测计划：那份按"每个协议一条 L1 + 一条 L2"铺开，
        // 而它们打的是同一个 modelsUrl（`EndpointNormalizer` 里模型列表不分协议）。
        // 复用的后果不只是白发请求——每条任务各带一个 protocol，同一份响应会被按协议
        // 各归一桶、各写一套行，界面上每个模型就出现两遍。见 `ProbePlanBuilder` 的说明。
        val plannedTasks = ProbePlanBuilder.buildModelListTasks(listOf(provider)) { pid ->
            selectedKeys.filter { it.providerId == pid }
        }

        fun profileOf(id: Long?): ClientProfile? =
            profileList.firstOrNull { it.id == id } ?: defaultProfile

        val tasks = plannedTasks.mapNotNull { it.toTask(profileOf(it.clientProfileId)) }

        // "按了刷新、模型数一点没变"必须留一句为什么。钥匙在这里掉队有两种，都不报错：
        // 计划层不给这把 Key 排任务（没声明协议、或 baseUrl 坏，见 `ProbePlanBuilder.targetOf`），
        // 执行层丢掉排好的任务（密钥解不开：保险箱锁着，或密文与 AAD 对不上）。
        // 不写这一句的话，日志里那句 `keys=N tasks=M` 只是让人盯着两个数字猜。
        val unplannedKeys = selectedKeys.mapNotNull { it.id }.toSet() -
            plannedTasks.mapNotNull { it.keyId }.toSet()
        val droppedTasks = plannedTasks.size - tasks.size
        if (unplannedKeys.isNotEmpty() || droppedTasks > 0) {
            audit.record(
                level = LogLevel.WARN,
                category = LogCategory.PROBE,
                message = "model list refresh had nothing to send",
                detail = "provider=$providerId unplannedKeys=${unplannedKeys.size} " +
                    "droppedTasks=$droppedTasks",
                providerId = providerId,
            )
        }

        var ok = 0
        var fail = 0
        val fetchedByKey = mutableMapOf<Long, MutableMap<Protocol, MutableSet<String>>>()
        for (task in tasks) {
            val keyIdForTask = task.keyId ?: continue
            val taskKey = selectedKeys.firstOrNull { it.id == keyIdForTask }
            val response = engine.execute(
                ProbeRequest(
                    method = "GET",
                    url = task.url,
                    headers = task.headers,
                    body = null,
                    protocol = task.protocol,
                    timeoutMs = task.timeoutMs,
                ),
                allowInsecure = taskKey?.settings?.allowInsecure ?: false,
            )
            if (response.status == 429) {
                engine.onRateLimited(task.host)
                fail++
                break
            }

            val classification = ProbeClassifier.classify(
                status = response.status.takeIf { response.error == null },
                body = response.body,
                error = response.error,
                level = task.level,
                clientKeywords = clientKeywords,
                headers = response.headers,
            )
            if (classification.outcome == ProbeOutcome.SUCCESS) {
                ok++
            } else {
                fail++
            }

            // 模型列表拉取不该绕过用户关掉的密钥检测开关。
            if (taskKey?.settings?.probe?.keyValidity == true) {
                val scrubbedDetail = classification.detail?.let { redactor.scrub(it) }
                if (classification.health != null) {
                    keys.applyProbeResult(
                        id = keyIdForTask,
                        health = classification.health.wireName,
                        lastOutcome = classification.outcome.wireName,
                        detail = scrubbedDetail,
                        httpStatus = classification.httpStatus,
                        latencyMs = response.latencyMs,
                        checkedAt = now(),
                        okAt = if (classification.outcome == ProbeOutcome.SUCCESS) now() else null,
                    )
                } else {
                    keys.applyTransientOutcome(
                        id = keyIdForTask,
                        lastOutcome = classification.outcome.wireName,
                        detail = scrubbedDetail,
                        httpStatus = classification.httpStatus,
                        checkedAt = now(),
                    )
                }
            }

            if (classification.outcome == ProbeOutcome.SUCCESS) {
                applyParsedModels(response.body, task.protocol, taskKey, providerId, keyIdForTask, fetchedByKey)
            }
        }

        var discovered = 0
        fetchedByKey.forEach { (currentKeyId, byProtocol) ->
            byProtocol.forEach { (protocol, modelIds) ->
                discovered += modelIds.size
                models.applyDiscovered(providerId, currentKeyId, protocol, modelIds.toList())
            }
        }

        audit.record(
            level = if (fail == 0) LogLevel.INFO else LogLevel.WARN,
            category = LogCategory.PROBE,
            message = "models refreshed",
            // `tasks` 与 `keys` 应当相等：每把 Key 恰好一次 GET。日志里两者差开就是
            // 有人又把探测计划拿来做模型列表刷新了（同一个 modelsUrl 会被发两三次）。
            detail = "provider=$providerId keys=${selectedKeys.size} tasks=${tasks.size} " +
                "ok=$ok fail=$fail models=$discovered",
        )
        return ModelRefreshResult(discovered = discovered, failed = fail > 0 && ok == 0)
    }

    /**
     * 每把 Key 的"模型列表候选任务"：taskId → keyId。
     *
     * 计划里同一把 Key 可能同时挂着 L1（每个协议一条）和 L2，而它们的 url 都是同一个
     * `modelsUrl`——模型列表端点不分协议。逐条解析等于把同一份响应按协议各归一桶、各写
     * 一套行（`ModelMerger` 的"消失即删"按 `discoveredVia` 分协议，两套行互不清理），
     * 界面上就是每个模型出现两遍。所以一把 Key 本轮**只折一次**。
     *
     * 这里是"候选"而不是从前那条"首选"：曾经只把一把 Key 绑到唯一一个任务上（L2 优先），
     * 于是当 L2 被 host 门闸或 429 跳过（跳过的结果 `body = null`）而同一份 url 的 L1
     * 其实成功带回列表时，那份列表被整个丢掉——用户按了刷新、日志里只有一句
     * 「model list skipped」，模型数原地不动。既然两条任务打的是同一个端点、拿的是同一份
     * 响应，谁先带回可用内容就用谁才是对的顺序。
     *
     * 剩下的口径差别只有上游没给 `supported_endpoint_types` 时解析器用什么协议兜底，
     * 而那已经被 [ModelMerger.oneProtocolPerModel] 收敛到这把 Key 声明的首选取。
     */
    private fun modelSourceCandidatesByKeyId(
        tasks: List<ProbeTask>,
        keyById: Map<Long, ApiKey>,
    ): Map<String, Long> {
        val sources = mutableMapOf<String, Long>()
        for (task in tasks) {
            val keyId = task.keyId ?: continue
            val key = keyById[keyId] ?: continue
            if (!key.settings.probe.models) continue
            sources[task.id] = keyId
        }
        return sources
    }

    /**
     * 把一次 `/models` 响应折进"本轮发现的模型"累加表（累加表最后才会送进
     * [ModelRepository.applyDiscovered]，那里带着"消失即删"）。
     *
     * **只有 `Confirmed` 往下走**。`SuspiciousEmpty`（`data` 数组在、但一条带 id 的模型
     * 都没有）与 `Unparseable`（响应压根不是模型列表）都直接 return：一旦让它们进到
     * `applyDiscovered(emptyList())`，这把 Key 在本协议下发现过的模型会被整片删掉，而
     * "密钥能用却列不出任何模型"在中转站上几乎总是异常（登录网关、字段改名、上游在发布），
     * 不是真的没有。删错的代价是用户看到的模型列表凭空消失，不删的代价只是几行陈旧数据。
     *
     * 判定本身在解析层（`probe/ModelListParse`），这里只是"不落地"的那一半——
     * 仓库层（`data/RoomModelRepository`）不动，它照旧认为"空列表 = 确实没有"。
     *
     * **一个模型只折进一个协议**（[ModelMerger.oneProtocolPerModel]）。解析器会把
     * `supported_endpoint_types: ["openai"]` 摊成 chat + responses，那是解析层的事实；
     * 照原样落库就是同一个模型两行，而"消失即删"按协议各管一套、谁也清不掉谁，
     * 于是每刷新一次重复就重新长出来一次。
     *
     * @return 这次真的折进了列表（`Confirmed`）才算 true。`SuspiciousEmpty` / `Unparseable`
     *   返回 false，好让调用方把"这把 Key 本轮还没吃到列表"这件事留着——同一把 Key 的
     *   另一条候选任务（同 url、同响应）还有可能带回复用。少了这个返回值，一次响应头不对的
     *   任务就会把这把 Key 永久占住，模型数原地不动。
     */
    private suspend fun applyParsedModels(
        body: String?,
        protocol: Protocol,
        key: ApiKey?,
        providerId: Long,
        keyId: Long,
        accumulator: MutableMap<Long, MutableMap<Protocol, MutableSet<String>>>,
    ): Boolean {
        val allowedProtocols = key?.settings?.supportedProtocols ?: emptySet()
        return when (val parsed = ModelListParser.parse(body, protocol)) {
            is ModelListParse.Confirmed -> {
                ModelMerger.oneProtocolPerModel(
                    discovered = parsed.models,
                    allowed = allowedProtocols,
                    preferred = allowedProtocols.firstOrNull(),
                ).forEach { model ->
                    accumulator
                        .getOrPut(keyId) { mutableMapOf() }
                        .getOrPut(model.protocol) { mutableSetOf() }
                        .add(model.modelId)
                }
                true
            }

            ModelListParse.SuspiciousEmpty -> {
                audit.record(
                    level = LogLevel.WARN,
                    category = LogCategory.PROBE,
                    message = "model list skipped: no usable entries",
                    detail = "provider=$providerId key=$keyId protocol=${protocol.wireName}",
                    providerId = providerId,
                    keyId = keyId,
                )
                false
            }

            ModelListParse.Unparseable -> {
                audit.record(
                    level = LogLevel.WARN,
                    category = LogCategory.PROBE,
                    message = "model list skipped: not a model list",
                    detail = "provider=$providerId key=$keyId protocol=${protocol.wireName}",
                    providerId = providerId,
                    keyId = keyId,
                )
                false
            }
        }
    }

    /**
     * 只刷新供应商可达性延迟。这个入口绝不 reveal 密钥，也不更新密钥健康，
     * 供仪表盘 / 管理页右上角的刷新按钮与余额查询并列使用。
     *
     * @param providerId 只查这一家的官网；null 表示全部（首页 / 管理页的批量刷新）。
     */
    fun refreshReachability(providerId: Long? = null): Boolean {
        if (statusJob?.isActive == true) return false
        if (!session.isUnlocked) return false
        statusJob = scope.launch {
            autoLocker.pauseIdleLock()
            try {
                refreshReachabilityInner(providerId)
            } finally {
                autoLocker.resumeIdleLock()
            }
        }
        return true
    }

    private suspend fun refreshReachabilityInner(providerId: Long?) {
        // 只查**用户允许查**的那些家：`checkWebsite` 是"要不要让这台设备去敲那家的站"
        // （v7 起的开关，默认关）。没有这个开关就去 ping 用户没点过头的地址，
        // 等于替他做了个对外请求的决定。
        val providerList = providers.observeSummaries().first()
            .map { it.provider }
            .filter {
                it.checkWebsite &&
                    !it.websiteUrl.isNullOrBlank() &&
                    (providerId == null || it.id == providerId)
            }
        if (providerList.isEmpty()) return

        for (provider in providerList) {
            val started = now()
            val response = engine.execute(
                ProbeRequest(
                    method = "GET",
                    url = provider.websiteUrl!!,
                    headers = emptyList(),
                    body = null,
                    protocol = null,
                ),
                allowInsecure = false,
            )
            providers.updateWebsiteStatus(
                id = provider.id,
                latencyMs = response.latencyMs,
                checkedAt = started,
                error = response.error?.message
                    ?: if (response.status !in 200..399) "http ${response.status}" else null,
            )
        }

        audit.record(
            level = LogLevel.INFO,
            category = LogCategory.PROBE,
            message = "provider websites checked",
            detail = "providers=${providerList.size}",
        )
    }
    /**
     * 模型可达性探测（快捷）。**只能手动触发**：长按模型发一次极短推理请求，
     * 自动路径（[start] / [probeProvider] / [probeKey]）永远不生成 L3 任务——
     * 它必然花钱，红线 36。
     *
     * 协议按 [MODEL_PROBE_PROTOCOL_ORDER]（Chat → Anthropic）试探，先成功者为准；
     * 所以这里不接"模型行上记的协议"这个参数——那个值只在展示层用。
     */
    fun probeModel(providerId: Long, keyId: Long, modelId: String): Boolean {
        if (quickModelJob?.isActive == true) return false
        if (!session.isUnlocked) return false
        quickModelJob = scope.launch {
            autoLocker.pauseIdleLock()
            try {
                probeModelInner(providerId, keyId, modelId)
            } finally {
                autoLocker.resumeIdleLock()
            }
        }
        return true
    }

    private suspend fun probeModelInner(
        providerId: Long,
        keyId: Long,
        modelId: String,
    ) {
        val key = keys.find(keyId) ?: return
        if (!key.settings.probe.enabled || !key.settings.probe.modelReachability || !key.settings.probe.quickModelProbe) return
        val keySettings = key.settings
        val endpoints = when (val result = normalizeBaseUrl(keySettings.apiBaseUrl, keySettings.pathOverrides)) {
            is NormalizeResult.Ok -> result.endpoints
            is NormalizeResult.Err -> return
        }

        val secret = try {
            keys.reveal(keyId)
        } catch (_: Exception) {
            return
        }

        try {
            knownSecrets.add(secret)
            val profileList = clientProfiles.observeAll().first()
            val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
            val profile = profileList.firstOrNull { it.id == keySettings.clientProfileId } ?: defaultProfile
            val clientKeywords = settings.observeClientKeywords().first()

            // Chat 优先、失败回落 Anthropic；两个都不行才算不可达。
            var lastAttempt: ModelProbeAttempt? = null
            for (candidate in MODEL_PROBE_PROTOCOL_ORDER) {
                val url = endpoints.byProtocol[candidate] ?: continue
                val attempt = probeModelOnce(
                    keySettings = keySettings,
                    endpoints = endpoints,
                    profile = profile,
                    secret = secret,
                    modelId = modelId,
                    protocol = candidate,
                    clientKeywords = clientKeywords,
                    prompt = ProbeRequestBuilder.QUICK_REACHABILITY_PROMPT,
                )
                lastAttempt = attempt
                if (attempt.classification.outcome == ProbeOutcome.SUCCESS) break
            }
            val attempt = lastAttempt ?: return

            val classification = attempt.classification
            val detail = classification.detail?.let { redactor.scrub(it) }
            // 模型行按 keyId + modelId 定位：这一轮试的可能不是那一行记的协议，
            // 拿协议一起查会查不到、于是白探一遍（不写回任何东西）。
            val modelRowId = modelIdToRow(providerId, keyId, modelId)
            if (modelRowId == 0L) return

            val state = modelProbeStateOf(classification)
            if (state == ModelProbeState.UNKNOWN) {
                models.applyTransientOutcome(
                    id = modelRowId,
                    lastOutcome = classification.outcome.wireName,
                    detail = detail,
                    probedAt = now(),
                )
            } else {
                models.applyProbeResult(
                    id = modelRowId,
                    state = state.wireName,
                    lastOutcome = classification.outcome.wireName,
                    detail = detail,
                    latencyMs = attempt.latencyMs,
                    probedAt = now(),
                )
            }
            audit.record(
                level = if (classification.outcome == ProbeOutcome.SUCCESS) LogLevel.INFO else LogLevel.WARN,
                category = LogCategory.PROBE,
                message = "model quick probe finished",
                detail = "provider=$providerId key=$keyId protocol=${attempt.protocol.wireName} " +
                    "outcome=${classification.outcome.wireName}",
                providerId = providerId,
                keyId = keyId,
            )
        } finally {
            secret.zeroize()
        }
    }

    /** 一次模型探测尝试的结果。带上协议，因为回落时"哪条路由成的"是要报给用户的。 */
    private data class ModelProbeAttempt(
        val protocol: Protocol,
        val classification: Classification,
        val latencyMs: Long?,
    )

    /**
     * 单次模型探测：按 [protocol] 造推理请求、发出去、分类。
     *
     * 抽出来是为了让"chat 失败回落 Anthropic"是两次**同样的**请求，只有协议不同——
     * 内联两遍迟早出现两边头不一样、body 不一样这种只有某些站点才暴露的差异。
     */
    private suspend fun probeModelOnce(
        keySettings: KeySettings,
        endpoints: ApiEndpointSet,
        profile: ClientProfile?,
        secret: CharArray,
        modelId: String,
        protocol: Protocol,
        clientKeywords: List<String>,
        prompt: String,
    ): ModelProbeAttempt {
        val baseRequest = ProbeRequestBuilder.inference(
            url = endpoints.byProtocol[protocol] ?: "",
            protocol = protocol,
            apiKey = secret,
            modelId = modelId,
            authStyle = keySettings.authStyle,
            prompt = prompt,
            timeoutMs = timeoutMsOf(keySettings),
        )
        val patchedBody = mergeBodyPatch(
            baseRequest.body ?: "",
            profile?.bodyPatch ?: "{}",
        )
        val request = baseRequest.copy(
            headers = HeaderAssembler.assemble(
                baseHeaders = BASE_HEADERS,
                profile = profile,
                authHeaders = baseRequest.headers,
                placeholders = placeholders,
            ).headers,
            body = HeaderAssembler.expandPlaceholders(patchedBody, placeholders),
        )
        val response = engine.execute(request, allowInsecure = keySettings.allowInsecure)
        val classification = ProbeClassifier.classify(
            status = response.status.takeIf { response.error == null },
            body = response.body,
            error = response.error,
            level = ProbeLevel.L3_MODEL,
            clientKeywords = clientKeywords,
            headers = response.headers,
        )
        // 先分类再通知门闸：`Retry-After` 是从响应头里读出来的，顺序反了就等于没读。
        if (response.status == 429 && response.error == null) {
            engine.onRateLimited(hostOf(keySettings.apiRoot), classification.retryAfterMs)
        }
        return ModelProbeAttempt(
            protocol = protocol,
            classification = classification,
            latencyMs = response.latencyMs,
        )
    }

    /** 这把 Key 的 per-request 超时（毫秒）。0 / 负数按"没填"处理，见 `ProbePlanBuilder`。 */
    private fun timeoutMsOf(settings: KeySettings): Long? =
        settings.timeoutSeconds?.takeIf { it > 0 }?.times(1_000L)

    /** `scheme://host[:port]/path` → host。门闸按 host 记账，所以哪条路径都要算得一样。 */
    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringAfterLast('@').substringBefore(':')

    /**
     * 模型行定位：**按 keyId + modelId，不带协议**。
     *
     * 回落探测时用的协议可能与该行记的协议不同，带上协议就查不到那一行了。
     */
    private suspend fun modelIdToRow(
        providerId: Long,
        keyId: Long,
        modelId: String,
    ): Long = models.observeByProvider(providerId).first()
        .firstOrNull { it.keyId == keyId && it.modelId == modelId }
        ?.id ?: 0L

    private fun startScoped(runScope: String, filter: (PlannedTask) -> Boolean): Boolean {
        if (running) return false
        if (!session.isUnlocked) return false

        currentJob = scope.launch {
            runRound(runScope, filter)
        }
        return true
    }

    /** 取消当前一轮。是真的 [Job.cancel]（§8.5），不是设个标志位。 */
    fun cancel() {
        val job = currentJob
        currentJob = null
        if (job?.isActive == true) {
            job.cancel()
            // 不在这里清 `_progress`：正在协程里的收尾跑在 NonCancellable 里，它会写
            // "这一轮被取消"并把进度摘掉。抢先把进度清了，明细页上那句"上次探测"会
            // 先跳到旧的那一轮，看着像取消没生效。
        } else {
            // 已经没有活着的轮次（跑完被回收、或上一轮是从异常里逃出去的）却还留着进度，
            // 那这个按钮就是用户唯一的出路——按下去必须真的把「正在请求 N/M」清掉，
            // 不能只把一句"已停止"报给他而界面照旧。
            _progress.value = null
        }
    }

    /** 锁定 / 退出时由 [ProbeSession] 的持有方调用：停掉正在跑的探测与模型拉取。 */
    fun onLock() {
        currentJob?.cancel()
        currentJob = null
        modelJob?.cancel()
        modelJob = null
        statusJob?.cancel()
        statusJob = null
        quickModelJob?.cancel()
        quickModelJob = null
    }

    // ------------------------------------------------------------------ 一轮

    /**
     * 跑一轮。@param scope `probe_runs.scope` 的值——全量是 `"all"`，重试是 `"retry"`。
     * @param filter 在 [ProbePlan] 产出的骨架任务上做二级过滤：全量恒 true，重试只留失败项。
     *
     * 这一层是**兜底的最后一道**。[runRoundInner] 里那段 collect 有自己的 catch，但 collect
     * 之外还剩两段没人接：置进度之前那一段（已经插入 `probe_runs`，接着清名单、建计划），
     * 以及收尾那一段（折模型列表、补发独立 GET）。任何一处抛出后的后果都是界面上的死态——
     * `_progress` 停在 `running = true`，仪表盘永远显示「正在请求 N/M」，而它那句
     * "查看明细"的显示条件是 `progress == null`，于是用户连停止按钮都找不到，只能杀进程。
     * 现在无论从哪里逃出，都把进度摘掉并留一条 ERROR 说明为什么中断。
     */
    private suspend fun runRound(scope: String, filter: (PlannedTask) -> Boolean) {
        // 探测进行中挂起前台空闲锁定（§7.4 / 红线 28）：一轮预算 120 秒，用户不摸屏幕
        // 是常态，不挂起就会自己锁掉自己。finally 保证任何退出路径（含取消 / 锁定）都恢复。
        autoLocker.pauseIdleLock()
        try {
            runRoundInner(scope, filter)
        } catch (failure: Throwable) {
            // 不 rethrow：`scope` 那边只有 ScopeCrashGuard（一条空 handler），异常冒过去
            // 既不杀应用也不留痕迹，而界面已经坏了。写日志这件事本身也可能失败，所以
            // 再兜一层——这里的任何一句都不许再把上面那个"摘进度"跳过去。
            runCatching {
                audit.record(
                    level = LogLevel.ERROR,
                    category = LogCategory.PROBE,
                    message = "probe round crashed",
                    detail = "${failure::class.simpleName}: ${failure.message}",
                )
            }
        } finally {
            // 正常路径由 [finishRun] 第一句就清掉；这一句只对"没走到 finishRun"的出口负责。
            if (_progress.value?.running == true) _progress.value = null
            autoLocker.resumeIdleLock()
        }
    }

    private suspend fun runRoundInner(scope: String, filter: (PlannedTask) -> Boolean) {
        val startedAt = now()
        val runId = runRepository.insert(
            ProbeRun(scope = scope, startedAt = startedAt),
        )

        // 本轮的 429 名单从空开始（间隔不清——撞过就是真节流，跨轮有效）。
        engine.clearRateLimitedMarks()
        // 先取一份上一轮的快照：重试范围要在它基础上留行，见下面 `_lastRound` 的起头。
        val previousRound = _lastRound.value

        // 拉全量供应商、密钥、预设（只碰明文列，不解密密钥本身，§6.1 推论 3）。
        val providerList: List<Provider> = providers.observeSummaries().first().map { it.provider }
        val allKeys: List<ApiKey> = keys.observeAll().first()
        val profileList: List<ClientProfile> = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        // 客户端拦截关键词来自设置（§13.4 探测设置页），默认 §8.2 的内置表。
        val clientKeywords = this.settings.observeClientKeywords().first()
        // 客户端嗅探开关（§8.2）：关掉后 CLIENT_BLOCKED 只保留结论、不换预设重试。
        val sniffEnabled = settings.observeSniffClientProfile().first()

        val plan: ProbePlan = ProbePlanBuilder.build(providerList) { pid ->
            allKeys.filter { it.providerId == pid }
        }

        fun profileOf(id: Long?): ClientProfile? =
            profileList.firstOrNull { it.id == id } ?: defaultProfile

        // 把骨架任务组装成完整 ProbeTask（含完整头）。reveal 在这里发生。
        // 重试时只留 filter 命中的任务（§13.4 的"仅重试失败项"）。
        val tasks = mutableListOf<ProbeTask>()
        for (planned in plan.tasks) {
            if (!filter(planned)) continue
            planned.toTask(profileOf(planned.clientProfileId))?.let { tasks += it }
        }

        // 累计快照起头（§13.4 明细页只读这一份）：
        // - 重试：留下上一轮**这一轮不重跑**的那些行，重跑到的那几条由新结果覆盖。
        //   原来这里每轮都无脑清空，而 `retryFailed` 的注释写着"其它原样保留"——
        //   两句里只有一句是对的，现在以行为准：全清过一次重试会把上一轮的成功记录
        //   一起弄丢，明细页突然只剩几行，用户看不出"其它家本来是好的"。
        // - 其它范围（全量 / 单家 / 单 Key）：从空开始，这一轮跑完就是完整结论。
        val scheduledIds = tasks.map { it.id }.toSet()
        _lastRound.value = if (scope == RETRY_SCOPE) {
            previousRound.filter { it.taskId !in scheduledIds }
        } else {
            emptyList()
        }

        val providerTotal = tasks.count { it.level == ProbeLevel.L1_REACHABILITY }
        val keyTotal = tasks.count { it.level == ProbeLevel.L2_KEY_VALIDITY }
        var done = 0
        var ok = 0
        var fail = 0
        // 被跳过的项单独记：`probe_runs.done` 的口径是"真正发过请求的项"，明细页摘要里的
        // "未探测 N"就是 `total - done`（见 `UiMapping.toSummary`）。要是把跳过项也计进
        // done，那个减法就永远得 0 了。进度条反过来要能走到终点，所以它显示 done + skipped。
        var skipped = 0
        var providerSkipped = 0
        var keySkipped = 0
        var providerDone = 0
        var providerOk = 0
        var providerFail = 0
        var keyDone = 0
        var keyOk = 0
        var keyFail = 0

        if (tasks.isEmpty()) {
            finishRun(
                runId = runId,
                scope = scope,
                startedAt = startedAt,
                total = 0,
                done = 0,
                ok = 0,
                fail = 0,
                providerTotal = 0,
                providerDone = 0,
                providerOk = 0,
                providerFail = 0,
                keyTotal = 0,
                keyDone = 0,
                keyOk = 0,
                keyFail = 0,
                cancelled = false,
            )
            return
        }

        _progress.value = ProbeProgress(
            runId = runId,
            running = true,
            done = 0,
            total = tasks.size,
            providerTotal = providerTotal,
            keyTotal = keyTotal,
        )

        val transport = ProbeTransport { request, allowInsecure ->
            engine.execute(request, allowInsecure)
        }

        val orchestrator = ProbeOrchestrator(
            transport = transport,
            nowMillis = now,
            onRateLimited = { host, retryAfterMs ->
                engine.onRateLimited(host, retryAfterMs)
            },
            budget = ProbeBudget(),
            clientKeywords = clientKeywords,
        )

        val taskById = tasks.associateBy { it.id }
        val keyById = allKeys.associateBy { it.id }
        val providerIdByKey = tasks.mapNotNull { task ->
            task.keyId?.let { it to task.providerId }
        }.toMap()
        val fetchedModels = mutableMapOf<Long, MutableMap<Protocol, MutableSet<String>>>()
        val modelCandidates = modelSourceCandidatesByKeyId(tasks, keyById)
        // 本轮已经把模型列表折进去的 Key：一把 Key 只折一次，谁先带回可用内容算谁的。
        val modelsFolded = mutableSetOf<Long>()

        try {
            orchestrator.run(tasks, plan.perHostKeyAndModelCount).collect { result ->
                // 客户端被拦（手动探测）→ 先换鉴权头再按序试预设（§8.2）。修正成功就用
                // 修正后的结论覆盖这一项，否则保留 CLIENT_BLOCKED。
                var final = result
                if (result.health == KeyHealth.CLIENT_BLOCKED && result.keyId != null) {
                    if (sniffEnabled) {
                        trySniff(
                            blocked = result,
                            task = taskById[result.taskId],
                            profiles = profileList,
                            defaultProfile = defaultProfile,
                            clientKeywords = clientKeywords,
                        )?.let { final = it }
                    }
                }

                // 计数：跳过项单独一档，见 `skipped` 的声明处。被跳过的项连请求都没发出去，
                // 既不该写 `checkedAt`，也不该把上一轮的好结论覆盖掉。
                if (final.outcome == ProbeOutcome.SKIPPED) {
                    skipped++
                    when (taskById[result.taskId]?.level) {
                        ProbeLevel.L1_REACHABILITY -> providerSkipped++
                        ProbeLevel.L2_KEY_VALIDITY -> keySkipped++
                        else -> Unit
                    }
                } else {
                    done++
                    when (final.outcome) {
                        ProbeOutcome.SUCCESS -> ok++
                        ProbeOutcome.CANCELLED -> Unit
                        else -> fail++
                    }
                    when (taskById[result.taskId]?.level) {
                        ProbeLevel.L1_REACHABILITY -> {
                            providerDone++
                            when (final.outcome) {
                                ProbeOutcome.SUCCESS -> providerOk++
                                ProbeOutcome.CANCELLED -> Unit
                                else -> providerFail++
                            }
                        }
                        ProbeLevel.L2_KEY_VALIDITY -> {
                            keyDone++
                            when (final.outcome) {
                                ProbeOutcome.SUCCESS -> keyOk++
                                ProbeOutcome.CANCELLED -> Unit
                                else -> keyFail++
                            }
                        }
                        else -> Unit
                    }
                }
                // 红线 32：detail 是上游 message 前 200 字符，上游会回显 key 前缀 / 后缀 4 位 /
                // base64 访问令牌，落库与推流前必须脱敏。在这里统一脱敏一次，`persist`、
                // `_results`、`_lastRound` 三处拿到的都是脱敏后的同一份。
                val scrubbed = if (final.detail != null) {
                    final.copy(detail = redactor.scrub(final.detail))
                } else {
                    final
                }
                // 模型列表检测开启时，这把 Key 的 models 响应就是它的模型列表，不再额外
                // 发一遍 GET。一把 Key 只折一次（见 [modelSourceCandidatesByKeyId]）：L1 与
                // L2 打的是同一个 modelsUrl，两条都解析就会按协议各归一桶、各写一套行，
                // 界面上每个模型出现两遍。这里只累积，三路合并等本轮流结束后统一做。
                val sourceTask = taskById[result.taskId]
                val modelsKeyId = modelCandidates[result.taskId]
                if (sourceTask != null && modelsKeyId != null && modelsKeyId !in modelsFolded) {
                    if (applyParsedModels(
                            body = result.body,
                            protocol = sourceTask.protocol,
                            key = keyById[modelsKeyId],
                            providerId = sourceTask.providerId,
                            keyId = modelsKeyId,
                            accumulator = fetchedModels,
                        )
                    ) {
                        modelsFolded += modelsKeyId
                    }
                }

                // SKIPPED 不落库（红线 11 + §8.4：跳过的连 checkedAt 都不写）。
                // 不落到库里的同时要推给界面：明细页的"本轮未探测"分组靠的就是这一条。
                if (scrubbed.outcome != ProbeOutcome.SKIPPED) persist(scrubbed, now())
                _results.tryEmit(scrubbed)
                _lastRound.value = _lastRound.value + scrubbed
                _progress.value = ProbeProgress(
                    runId = runId,
                    running = true,
                    // 进度条显示"已结算"（跑过的 + 被跳过的），否则会永远停在 total 之前：
                    // 被跳过的项不发请求，但它们的这一轮已经结束了。库里的 `done` 仍是
                    // "真正跑过请求的项"，摘要卡上的"未探测 N"继续由 `total - done` 算。
                    done = done + skipped,
                    total = tasks.size,
                    // 刚结算这一项探的是哪家。仪表板上那句"正在请求 N/M · host"第三段一直空着，
                    // 就是因为以前没人给它：尾部会悬一个孤零零的「·」。
                    currentHost = taskById[result.taskId]?.host,
                    providerDone = providerDone + providerSkipped,
                    providerTotal = providerTotal,
                    providerOk = providerOk,
                    providerFail = providerFail,
                    keyDone = keyDone + keySkipped,
                    keyTotal = keyTotal,
                    keyOk = keyOk,
                    keyFail = keyFail,
                )
            }
        } catch (_: CancellationException) {
            // 取消：已落库的结果保留，probe_runs 标 cancelled。
            //
            // **收尾必须跑在 NonCancellable 里**。这一条协程此刻已经是"已取消"状态，
            // 里面任何挂起调用（Room 写、审计日志）都会立刻再抛一次 CancellationException，
            // 于是加了"停止探测"按钮之后：用户点停止，`probe_runs` 那一行永远停在
            // finishedAt = null、done = 0 的半截状态，明细页显示"上次探测：从未"。
            // 这里要写的恰好是"这一轮被取消了"这个事实，不能被取消本身打断。
            withContext(NonCancellable) {
                finishRun(
                    runId = runId,
                    scope = scope,
                    startedAt = startedAt,
                    total = tasks.size,
                    done = done,
                    ok = ok,
                    fail = fail,
                    providerTotal = providerTotal,
                    providerDone = providerDone,
                    providerOk = providerOk,
                    providerFail = providerFail,
                    keyTotal = keyTotal,
                    keyDone = keyDone,
                    keyOk = keyOk,
                    keyFail = keyFail,
                    cancelled = true,
                )
            }
            return
        } catch (failure: Throwable) {
            // 不是"被取消"，是探测途中真的出了错：收尾写库撞 SQLITE_BUSY、磁盘满、库被系统回收。
            // 以前这一支没人接，后果是双重的——`finishRun` 整个被跳过，进度永久停在
            // "正在请求 N/M"、明细页永久显示"停止探测"并把重试藏掉；同一条异常同时冒到
            // `scope.launch` 杀进程。这里两件事一起收口：本轮按"跑到哪算哪"结案，
            // 并记一条 ERROR 说清为什么中断（写不进日志也不能再抛出去，那等于没兜）。
            withContext(NonCancellable) {
                runCatching {
                    finishRun(
                        runId = runId,
                        scope = scope,
                        startedAt = startedAt,
                        total = tasks.size,
                        done = done,
                        ok = ok,
                        fail = fail,
                        providerTotal = providerTotal,
                        providerDone = providerDone,
                        providerOk = providerOk,
                        providerFail = providerFail,
                        keyTotal = keyTotal,
                        keyDone = keyDone,
                        keyOk = keyOk,
                        keyFail = keyFail,
                        cancelled = false,
                    )
                }
                runCatching {
                    audit.record(
                        level = LogLevel.ERROR,
                        category = LogCategory.PROBE,
                        message = "probe round aborted",
                        detail = "${failure::class.simpleName}: ${failure.message}",
                        runId = runId,
                    )
                }
            }
            return
        }

        // 折模型列表这一段以前写在 finishRun 之前的裸位置上：它一抛（`applyDiscovered` 撞
        // SQLITE_BUSY、那趟补发的 GET 出错），下面的 finishRun 整个被跳过，于是同时留下两个
        // 现场——`probe_runs` 一行永远 `finishedAt = null`，以及 `_progress` 永远 running。
        // 探测结果本身此刻已经全部落库，"把列表折进去"这一步失败不该让这一轮结不了案，
        // 所以这里报一条 WARN 后继续收尾。
        runCatching {
            fetchedModels.forEach { (keyId, byProtocol) ->
                val currentProviderId = providerIdByKey[keyId] ?: return@forEach
                byProtocol.forEach { (protocol, modelIds) ->
                    models.applyDiscovered(currentProviderId, keyId, protocol, modelIds.toList())
                }
            }

            // 本轮一条任务都没摊上的 Key（可达性与密钥有效性都关着）在这里补一次独立 GET。
            // 少了这一趟，"只开模型列表自动更新"的 Key 永远拉不到列表——计划本身只按前两个
            // 开关铺任务。`buildModelListTasks` 只看 `probe.models`，所以这里确实发得出请求。
            allKeys
                .filter { key ->
                    key.settings.probe.enabled && key.settings.probe.models &&
                        tasks.none { it.keyId == key.id }
                }
                .forEach { key -> refreshModelsInner(key.providerId, key.id) }
        }.onFailure { failure ->
            withContext(NonCancellable) {
                runCatching {
                    audit.record(
                        level = LogLevel.WARN,
                        category = LogCategory.PROBE,
                        message = "model fold-in failed, round still closed",
                        detail = "${failure::class.simpleName}: ${failure.message}",
                        runId = runId,
                    )
                }
            }
        }

        finishRun(
            runId = runId,
            scope = scope,
            startedAt = startedAt,
            total = tasks.size,
            done = done,
            ok = ok,
            fail = fail,
            providerTotal = providerTotal,
            providerDone = providerDone,
            providerOk = providerOk,
            providerFail = providerFail,
            keyTotal = keyTotal,
            keyDone = keyDone,
            keyOk = keyOk,
            keyFail = keyFail,
            cancelled = false,
        )
    }

    private suspend fun finishRun(
        runId: Long,
        scope: String,
        startedAt: Long,
        total: Int,
        done: Int,
        ok: Int,
        fail: Int,
        providerTotal: Int,
        providerDone: Int,
        providerOk: Int,
        providerFail: Int,
        keyTotal: Int,
        keyDone: Int,
        keyOk: Int,
        keyFail: Int,
        cancelled: Boolean,
    ) {
        // **先把"进行中"摘掉，再动库。** 下面三句写库任何一句抛了，界面都不该继续画
        // "正在请求 N/M"、明细页也不该继续把"停止探测"顶在重试按钮上——那是个没有出路的态。
        // 摘早点没有代价：`running` 的判据是 `currentJob.isActive`，不是这条进度流。
        _progress.value = null
        runCatching {
            runRepository.update(
                ProbeRun(
                    id = runId,
                    scope = scope,
                    startedAt = startedAt,
                    finishedAt = now(),
                    total = total,
                    done = done,
                    okCount = ok,
                    failCount = fail,
                    providerTotal = providerTotal,
                    providerDone = providerDone,
                    providerOk = providerOk,
                    providerFail = providerFail,
                    keyTotal = keyTotal,
                    keyDone = keyDone,
                    keyOk = keyOk,
                    keyFail = keyFail,
                    cancelled = cancelled,
                ),
            )
        }
        // 一轮结束：记汇总日志。级别看有没有失败；取消不记 ERROR（它不是故障，§13.4）。
        val level = when {
            cancelled -> LogLevel.INFO
            fail > 0 -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        runCatching {
            audit.record(
                level = level,
                category = LogCategory.PROBE,
                message = if (cancelled) "probe cancelled" else "probe finished",
                detail = "total=$total ok=$ok fail=$fail cancelled=$cancelled",
                // 带上轮次：日志页/明细页要能回答"这一轮到底发生了什么"，`runId` 是唯一的线索。
                runId = runId,
            )
        }
        // 轮次收尾即裁一次条数：这张表每轮长一行，而明细页只看最近一轮。上限口径与
        // `LogMaintenance`（启动时那次）共用 `ProbeRunRepository.MAX_RUNS_KEPT`。
        runCatching { runRepository.trimToCount(ProbeRunRepository.MAX_RUNS_KEPT) }
        _roundResults.tryEmit(RoundResult(total = total, ok = ok, fail = fail, cancelled = cancelled))
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 骨架 → 完整任务。L2 在这里 reveal 密钥、构造鉴权头，再用 [HeaderAssembler] 把
     * base + 预设 UA/特征头 + 鉴权头拼成最终头（§8.2 的组装顺序）。
     *
     * 返回 null 表示这条任务该跳过：密钥 reveal 失败（锁定 / 解密失败）时不发请求。
     */
    private suspend fun PlannedTask.toTask(profile: ClientProfile?): ProbeTask? {
        val authHeaders = when (val revealId = keyId) {
            null -> ProbeRequestBuilder.authHeaders(protocol, null, authStyle)
            else -> {
                val secret = try {
                    keys.reveal(revealId)
                } catch (_: VaultLockedException) {
                    return null
                } catch (_: Exception) {
                    // 解密失败（密文坏了 / AAD 不匹配）：不发，别拿坏数据打请求。
                    return null
                }
                try {
                    // 登记已知明文：这把密钥的明文即将进入请求头，上游若在错误消息里
                    // 回显它的前缀/后缀 4 位，落库前的脱敏（红线 32 第一道）要能认出它。
                    knownSecrets.add(secret)
                    ProbeRequestBuilder.authHeaders(protocol, secret, authStyle)
                } finally {
                    secret.zeroize()
                }
            }
        }
        val assembled = HeaderAssembler.assemble(BASE_HEADERS, profile, authHeaders, placeholders)
        return ProbeTask(
            id = id,
            level = level,
            providerId = providerId,
            providerName = providerName,
            host = host,
            protocol = protocol,
            keyId = keyId,
            keyLabel = keyLabel,
            url = url,
            headers = assembled.headers,
            clientProfileId = clientProfileId,
            authStyle = authStyle,
            allowInsecure = allowInsecure,
            timeoutMs = timeoutMs,
        )
    }

    // ------------------------------------------------------------------ 嗅探

    /**
     * 客户端被拦后的重试（§8.2）。纯逻辑在 [SniffPlanBuilder]，这里只负责发请求 + 写回。
     *
     * 顺序：先换鉴权头（1 次）→ 再按序试内置预设（最多 4 个）。命中（不再 CLIENT_BLOCKED）
     * 就写回 `clientProfileId` / `authStyle` 并返回修正后的结论；撞 429 立即停（红线 29），
     * 结论留在 CLIENT_BLOCKED。全试完仍被拦时返回 null（保留原结论）。
     */
    private suspend fun trySniff(
        blocked: ProbeItemResult,
        task: ProbeTask?,
        profiles: List<ClientProfile>,
        defaultProfile: ClientProfile?,
        clientKeywords: List<String>,
    ): ProbeItemResult? {
        if (task == null) return null
        if (engine.isRateLimited(task.host)) return null
        val keyId = task.keyId ?: return null

        val secret = try {
            keys.reveal(keyId)
        } catch (_: Exception) {
            return null
        }

        try {
            // 同一把密钥在组装阶段已登记，这里再登记一次是防御性的（去重使其无害）：
            // 嗅探同样会把明文放进请求头，若上游回显它，脱敏第一道要能认出。
            knownSecrets.add(secret)
            val currentProfile = profiles.firstOrNull { it.id == task.clientProfileId } ?: defaultProfile
            val plan = SniffPlanBuilder.build(task.protocol, task.authStyle, task.clientProfileId, profiles)

            for (attempt in plan) {
                // 429 熔断：嗅探是本轮请求数的主要放大来源，撞了立刻停（红线 29）。
                if (engine.isRateLimited(task.host)) break

                val auth = ProbeRequestBuilder.authHeaders(task.protocol, secret, attempt.authStyle)
                val profile = attempt.profile ?: currentProfile
                val headers = HeaderAssembler.assemble(BASE_HEADERS, profile, auth, placeholders).headers
                val response = engine.execute(
                    ProbeRequest(
                        method = if (task.body == null) "GET" else "POST",
                        url = task.url,
                        headers = headers,
                        body = task.body,
                        protocol = task.protocol,
                        timeoutMs = task.timeoutMs,
                    ),
                    allowInsecure = task.allowInsecure,
                )

                val classification = ProbeClassifier.classify(
                    status = response.status.takeIf { response.error == null },
                    body = response.body,
                    error = response.error,
                    level = task.level,
                    clientKeywords = clientKeywords,
                    headers = response.headers,
                )

                // 撞 429：先记进门闸（间隔加倍 + 本轮名单），再停嗅探（红线 29）。
                // 分类在前是为了把 `Retry-After` 一起递进去。
                if (response.status == 429 && response.error == null) {
                    engine.onRateLimited(task.host, classification.retryAfterMs)
                    break
                }

                // 还是被拦 → 试下一个预设。
                if (classification.health == KeyHealth.CLIENT_BLOCKED) continue

                // 命中了。SUCCESS 才写回（其它如 UNAUTHORIZED 只说明"钥匙真坏了"，不该固化风格）。
                if (classification.outcome == ProbeOutcome.SUCCESS) {
                    writeBack(keyId, attempt)
                }
                return blocked.copy(
                    outcome = classification.outcome,
                    health = classification.health,
                    detail = classification.detail,
                    httpStatus = classification.httpStatus,
                    latencyMs = response.latencyMs,
                )
            }
            return null
        } finally {
            secret.zeroize()
        }
    }

    /** 嗅探命中后的写回：成功才固化到这把 Key 的设置上。 */
    private suspend fun writeBack(keyId: Long, attempt: SniffAttempt) {
        val key = keys.find(keyId) ?: return
        val settings = if (attempt.profile != null) {
            key.settings.copy(clientProfileId = attempt.profile.id)
        } else {
            key.settings.copy(authStyle = attempt.authStyle)
        }
        keys.updateSettings(keyId, settings)
        attempt.profile?.let { profile ->
            clientProfiles.findById(profile.id)?.let {
                clientProfiles.update(it.copy(verified = true))
            }
        }
    }
    // ------------------------------------------------------------------ 落库

    /**
     * 逐项落库（红线 11）。[ProbeItemResult.health] 非 null 才改 `api_keys.health`，
     * 否则只写瞬时结论——这与 `ApiKeyRepository.applyProbeResult` / `applyTransientOutcome`
     * 的两条 SQL 一一对应。
     */
    private suspend fun persist(result: ProbeItemResult, stamp: Long) {
        val keyId = result.keyId ?: return
        val health = result.health
        if (health != null) {
            keys.applyProbeResult(
                id = keyId,
                health = health.wireName,
                lastOutcome = result.outcome.wireName,
                detail = result.detail,
                httpStatus = result.httpStatus,
                latencyMs = result.latencyMs,
                checkedAt = stamp,
                okAt = if (result.outcome == ProbeOutcome.SUCCESS) stamp else null,
            )
        } else {
            keys.applyTransientOutcome(
                id = keyId,
                lastOutcome = result.outcome.wireName,
                detail = result.detail,
                httpStatus = result.httpStatus,
                checkedAt = stamp,
            )
        }
    }

    private companion object {
        /** §8.2 第 2 步的基础头。 */
        val BASE_HEADERS = listOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
        )

        /**
         * `probe_runs.scope` 里"仅重试失败项"那一条的取值。
         *
         * 做成常量而不是到处写字面量：`runRoundInner` 靠它决定"这一轮的累计快照要不要
         * 留上一轮的行"，而它必须和 [retryFailed] 传进去的那个值一模一样。
         */
        const val RETRY_SCOPE = "retry"
    }
}

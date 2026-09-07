package com.lc33.tokenvault.engine

import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.ProbeRun
import com.lc33.tokenvault.domain.repo.ProbeRunRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.domain.repo.SettingsRepository
import com.lc33.tokenvault.endpoint.HeaderAssembler
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.ProbeRequestBuilder
import com.lc33.tokenvault.endpoint.ProbeResponse
import com.lc33.tokenvault.net.HttpEngine
import com.lc33.tokenvault.probe.PlannedTask
import com.lc33.tokenvault.probe.ProbeBudget
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 探测引擎宿主（计划.md §8.5）。**不是 ViewModel**——探测要能跨页面存活，
 * 用户在仪表盘点"开始探测"后切去管理页，引擎不能跟着 ViewModel 一起死。
 *
 * 四条设计决定：
 *
 * 1. **自己的作用域**。内部 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentJob: Job? = null

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
     * 本轮已经撞过 429 的 host。编排器自己有一份（用来停后续任务），这里再记一份给
     * **嗅探**用——嗅探在编排器之外，撞到 429 必须立即停（红线 29）。
     */
    private val rateLimitedHosts = mutableSetOf<String>()

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
     * `taskId` 提出来当过滤条件，只重跑这些任务，其它原样保留在 [lastRound] 里。
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
        return startScoped(runScope = "retry") { it.id in retryIds }
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
        currentJob?.cancel()
        currentJob = null
    }

    /** 锁定 / 退出时由 [ProbeSession] 的持有方调用：停掉正在跑的探测。 */
    fun onLock() = cancel()

    // ------------------------------------------------------------------ 一轮

    /**
     * 跑一轮。@param scope `probe_runs.scope` 的值——全量是 `"all"`，重试是 `"retry"`。
     * @param filter 在 [ProbePlan] 产出的骨架任务上做二级过滤：全量恒 true，重试只留失败项。
     */
    private suspend fun runRound(scope: String, filter: (PlannedTask) -> Boolean) {
        // 探测进行中挂起前台空闲锁定（§7.4 / 红线 28）：一轮预算 120 秒，用户不摸屏幕
        // 是常态，不挂起就会自己锁掉自己。finally 保证任何退出路径（含取消 / 锁定）都恢复。
        autoLocker.pauseIdleLock()
        try {
            runRoundInner(scope, filter)
        } finally {
            autoLocker.resumeIdleLock()
        }
    }

    private suspend fun runRoundInner(scope: String, filter: (PlannedTask) -> Boolean) {
        val runId = runRepository.insert(
            ProbeRun(scope = scope, startedAt = now()),
        )

        // 新一轮：清空上一轮的累计快照，明细页随之刷新成"这一轮刚开始"。
        _lastRound.value = emptyList()
        rateLimitedHosts.clear()

        // 拉全量供应商、密钥、预设（只碰明文列，不解密密钥本身，§6.1 推论 3）。
        val providerList: List<Provider> = providers.observeSummaries().first().map { it.provider }
        val allKeys: List<ApiKey> = keys.observeAll().first()
        val profileList: List<ClientProfile> = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        // 客户端拦截关键词来自设置（§13.4 探测设置页），默认 §8.2 的内置表。
        val clientKeywords = settings.observeClientKeywords().first()
        // 客户端嗅探开关（§8.2）：关掉后 CLIENT_BLOCKED 只保留结论、不换预设重试。
        val sniffEnabled = settings.observeSniffClientProfile().first()

        val plan: ProbePlan = ProbePlanBuilder.build(providerList) { pid ->
            allKeys.filter { it.providerId == pid && it.enabled }
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

        if (tasks.isEmpty()) {
            finishRun(runId, total = 0, done = 0, ok = 0, fail = 0, cancelled = false)
            return
        }

        _progress.value = ProbeProgress(runId = runId, running = true, done = 0, total = tasks.size)

        val transport = ProbeTransport { request, allowInsecure ->
            engine.execute(request, allowInsecure)
        }

        val orchestrator = ProbeOrchestrator(
            transport = transport,
            nowMillis = now,
            hostIntervalMs = engine::hostIntervalMs,
            onRateLimited = { host ->
                engine.onRateLimited(host)
                rateLimitedHosts += host
            },
            budget = ProbeBudget(),
            clientKeywords = clientKeywords,
        )

        val taskById = tasks.associateBy { it.id }
        val providerById = providerList.associateBy { it.id }

        var done = 0
        var ok = 0
        var fail = 0
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
                            provider = providerById[result.providerId],
                            profiles = profileList,
                            defaultProfile = defaultProfile,
                            clientKeywords = clientKeywords,
                        )?.let { final = it }
                    }
                }

                done++
                when (final.outcome) {
                    ProbeOutcome.SUCCESS -> ok++
                    ProbeOutcome.SKIPPED, ProbeOutcome.CANCELLED -> Unit
                    else -> fail++
                }
                // 红线 32：detail 是上游 message 前 200 字符，上游会回显 key 前缀 / 后缀 4 位 /
                // base64 访问令牌，落库与推流前必须脱敏。在这里统一脱敏一次，`persist`、
                // `_results`、`_lastRound` 三处拿到的都是脱敏后的同一份。
                val scrubbed = if (final.detail != null) {
                    final.copy(detail = redactor.scrub(final.detail))
                } else {
                    final
                }
                persist(scrubbed, now())
                _results.tryEmit(scrubbed)
                _lastRound.value = _lastRound.value + scrubbed
                _progress.value = ProbeProgress(
                    runId = runId,
                    running = true,
                    done = done,
                    total = tasks.size,
                )
            }
        } catch (_: CancellationException) {
            // 取消：已落库的结果保留，probe_runs 标 cancelled。
            finishRun(runId, tasks.size, done, ok, fail, cancelled = true)
            return
        }

        finishRun(runId, tasks.size, done, ok, fail, cancelled = false)
    }

    private suspend fun finishRun(
        runId: Long,
        total: Int,
        done: Int,
        ok: Int,
        fail: Int,
        cancelled: Boolean,
    ) {
        runRepository.update(
            ProbeRun(
                id = runId,
                scope = "all",
                startedAt = now(),
                finishedAt = now(),
                total = total,
                done = done,
                okCount = ok,
                failCount = fail,
                cancelled = cancelled,
            ),
        )
        // 一轮结束：记汇总日志。级别看有没有失败；取消不记 ERROR（它不是故障，§13.4）。
        val level = when {
            cancelled -> LogLevel.INFO
            fail > 0 -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        audit.record(
            level = level,
            category = LogCategory.PROBE,
            message = if (cancelled) "probe cancelled" else "probe finished",
            detail = "total=$total ok=$ok fail=$fail cancelled=$cancelled",
        )
        _progress.value = null
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
            url = url,
            headers = assembled.headers,
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
        provider: Provider?,
        profiles: List<ClientProfile>,
        defaultProfile: ClientProfile?,
        clientKeywords: List<String>,
    ): ProbeItemResult? {
        if (task == null || provider == null) return null
        if (task.host in rateLimitedHosts) return null
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
            val currentProfile = profiles.firstOrNull { it.id == provider.clientProfileId } ?: defaultProfile
            val plan = SniffPlanBuilder.build(task.protocol, provider.authStyle, provider.clientProfileId, profiles)

            for (attempt in plan) {
                // 429 熔断：嗅探是本轮请求数的主要放大来源，撞了立刻停（红线 29）。
                if (task.host in rateLimitedHosts) break

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
                    ),
                    allowInsecure = false,
                )

                if (response.status == 429) {
                    rateLimitedHosts += task.host
                    engine.onRateLimited(task.host)
                    break
                }

                val classification = ProbeClassifier.classify(
                    status = response.status.takeIf { response.error == null },
                    body = response.body,
                    error = response.error,
                    level = task.level,
                    clientKeywords = clientKeywords,
                )

                // 还是被拦 → 试下一个预设。
                if (classification.health == KeyHealth.CLIENT_BLOCKED) continue

                // 命中了。SUCCESS 才写回（其它如 UNAUTHORIZED 只说明"钥匙真坏了"，不该固化风格）。
                if (classification.outcome == ProbeOutcome.SUCCESS) {
                    writeBack(provider, attempt)
                }
                return blocked.copy(
                    outcome = classification.outcome,
                    health = classification.health,
                    detail = classification.detail,
                    latencyMs = response.latencyMs,
                )
            }
            return null
        } finally {
            secret.zeroize()
        }
    }

    /** 嗅探命中后的写回。换预设命中 → 写 `clientProfileId` + 置 `verified`；换鉴权头命中 → 写 `authStyle`。 */
    private suspend fun writeBack(provider: Provider, attempt: SniffAttempt) {
        val profile = attempt.profile
        if (profile != null) {
            providers.save(provider.copy(clientProfileId = profile.id))
            clientProfiles.findById(profile.id)?.let {
                clientProfiles.update(it.copy(verified = true))
            }
        } else {
            providers.save(provider.copy(authStyle = attempt.authStyle))
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
                httpStatus = null,
                latencyMs = result.latencyMs,
                checkedAt = stamp,
                okAt = if (result.outcome == ProbeOutcome.SUCCESS) stamp else null,
            )
        } else {
            keys.applyTransientOutcome(
                id = keyId,
                lastOutcome = result.outcome.wireName,
                detail = result.detail,
                httpStatus = null,
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
    }
}

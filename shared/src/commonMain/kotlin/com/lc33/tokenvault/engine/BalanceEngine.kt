package com.lc33.tokenvault.engine

import com.lc33.tokenvault.balance.BalanceErrorReason
import com.lc33.tokenvault.balance.BalanceParseException
import com.lc33.tokenvault.balance.BalanceRegistry
import com.lc33.tokenvault.balance.NewApiAdapter
import com.lc33.tokenvault.crypto.KnownSecrets
import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.BalanceHistoryRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.endpoint.HeaderAssembler
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.endpoint.UpstreamMessage
import com.lc33.tokenvault.net.HttpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 余额查询引擎。
 *
 * v3 起余额配置在每一把 Key 上：同一供应商的不同 Key 可以使用不同余额适配器、
 * 不同访问令牌与不同站点地址。供应商页展示的金额只是 UI 对这些 Key 快照求和。
 */
class BalanceEngine constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val clientProfiles: ClientProfileRepository,
    private val engine: HttpEngine,
    private val audit: AuditLogRepository,
    /** 余额历史。每次成功刷到金额后（去重）追加一条，喂用量变化报告。 */
    private val history: BalanceHistoryRepository,
    /**
     * 登记这一轮 reveal 出来的明文，供 [Redactor] 的第一道使用。
     *
     * **这一步不能省**：余额接口（尤其 new-api 系的 `/api/user/self`）会把访问令牌
     * 连同邮箱一起回显，而现在响应体会落进日志——不登记就只剩正则兜底，而
     * base64 形态的令牌不匹配任何一条正则（见 [Redactor] 的说明）。
     */
    private val knownSecrets: KnownSecrets,
    private val now: () -> Long,
    private val placeholders: Map<String, String>,
) {
    /** 见 [rawOf]：跟着 [knownSecrets] 现读，不额外要一个构造参数。 */
    private val redactor = Redactor(knownSecrets = knownSecrets::snapshot)

    /**
     * 刷所有开了余额查询的 Key。
     *
     * 返回的是**这一趟的结果摘要**而不是"成功了几把"：调用点要能区分"压根没配"和
     * "配了但全挂"，只给一个 Int 的话这两种情况都是 0，界面就只能念一句"已刷新"——
     * 全挂的时候那句是假话，而它是用户唯一听到的那句。
     */
    suspend fun refreshAll(): BalanceRefreshOutcome =
        BalanceRefreshOutcome.of(
            keys.observeAll().first()
                .filter { it.settings.probe.enabled && it.settings.probe.balance }
                // `BalanceKind.NONE` 是没配查询类型，不算"试过"，不进分母。
                .filterNot { it.settings.balanceKind == BalanceKind.NONE }
                .map { key -> refreshSafely(key) },
        )

    suspend fun refresh(providerId: Long, keyId: Long? = null): BalanceRefreshOutcome {
        val provider = providers.find(providerId) ?: return BalanceRefreshOutcome.NONE
        val selected = keys.observeByProvider(providerId).first()
            .filter { keyId == null || it.id == keyId }
        return BalanceRefreshOutcome.of(
            selected.mapNotNull { key ->
                if (!key.settings.probe.enabled || !key.settings.probe.balance) {
                    null
                } else {
                    refreshSafely(key)
                }
            },
        )
    }

    /**
     * 跑一把 Key，任何意外都收成这一把的失败快照。
     *
     * [refreshKey] 内部已经挡了 `buildRequest` 与 `parse` 那两处，但还有一整类没挡：
     * 校准那一发、`updateSettings`、以及**记账那一发**（`AuditLogRepository.record`
     * 不防御）。它们只要抛出去，`refreshAll` 的循环就地终止，后面每一把 Key 都不再刷
     * 余额——而四个调用点全是 `runCatching`，用户看到的就是"余额永远不动、零提示"。
     * 兜底写在循环这一层，而不是每个可能抛的地方各挡一道：以后 [refreshKey] 里再多一次
     * 数据库写入，不需要有人记得再补一个 catch。
     */
    private suspend fun refreshSafely(key: ApiKey): BalanceSnapshot? = try {
        refreshKey(key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // 异常文本要截断：这一串接下来会进 `balanceError` 那一列、会显示在余额卡上，
        // 而驱动的异常消息偶尔是一整段堆栈描述。
        recordLocalFailure(key, failure.message?.takeIf { it.isNotBlank() }?.take(200) ?: "local_error")
    }

    private suspend fun refreshKey(key: ApiKey): BalanceSnapshot? {
        val settings = key.settings
        var effectiveSettings = settings
        var adapter = BalanceRegistry.forSettings(effectiveSettings) ?: return null

        val profileList = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        val profile = profileList.firstOrNull { it.id == settings.clientProfileId } ?: defaultProfile

        val token = if (adapter.kind.usesOwnToken) {
            // `CancellationException` 必须原样抛出去：它继承 `Exception`，一个
            // `catch (_: Exception)` 就会把"这一轮被锁屏取消了"读成"令牌解不开"，
            // 于是取消之后这一趟还在往下发请求。上一版就是这么写的。
            val opened = try {
                keys.revealBalanceToken(key.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return recordLocalFailure(key, BalanceErrorReason.TOKEN_UNDECRYPTABLE)
            }
            opened?.also { knownSecrets.add(it) }
                // 密文那一列是空的：选了需要独立令牌的查询类型，却压根没填令牌。
                ?: return recordLocalFailure(key, BalanceErrorReason.TOKEN_MISSING)
        } else {
            null
        }

        try {
            // 校准只做一次：`quotaCalibrated` 是"这台设备上已经从 /api/status 读到过
            // quota_per_unit"的记号。原来每次刷余额都先打一发 `/api/status` 再看结果，
            // 于是"查一次余额"实际是两个请求——而第二发拿到的换算比永远和库里那份一样。
            val newApiAdapter = adapter as? NewApiAdapter
            if (newApiAdapter != null && !settings.quotaCalibrated) {
                val calibrated = tryCalibrate(newApiAdapter, settings, profile)
                if (calibrated != null) {
                    effectiveSettings = settings.copy(quotaPerUnit = calibrated, quotaCalibrated = true)
                    keys.updateSettings(
                        id = key.id,
                        settings = effectiveSettings,
                    )
                    adapter = BalanceRegistry.forSettings(effectiveSettings) ?: return null
                }
            }

            val keySecret = if (adapter.kind.usesOwnToken) {
                null
            } else {
                val opened = try {
                    keys.reveal(key.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return recordLocalFailure(key, BalanceErrorReason.KEY_UNDECRYPTABLE)
                }
                opened.also { knownSecrets.add(it) }
            }
            try {
                // `buildRequest` 也会抛 `BalanceParseException`（`CustomJsonAdapter` 在 headers
                // 被配成对象/数组时抛 bad_headers_config / bad_header_value_*）。收成这一把 Key
                // 的失败快照而不是让它冒出去：这一发的原因跟"上游拒了"是两回事，用户能做的
                // 是回去改余额配置，没什么可等的。
                val request = try {
                    withClientProfile(
                        adapter.buildRequest(effectiveSettings, keySecret, token),
                        profile,
                    )
                } catch (error: BalanceParseException) {
                    return recordLocalFailure(key, error.reason)
                }
                val response = engine.execute(request, allowInsecure = effectiveSettings.allowInsecure)
                val snapshot = response.error?.let { error ->
                    BalanceSnapshot(
                        amount = null,
                        currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                        checkedAt = now(),
                        error = error.message ?: "network error",
                    )
                } ?: try {
                    adapter.parse(response.status, response.body).copy(checkedAt = now())
                } catch (error: BalanceParseException) {
                    BalanceSnapshot(
                        amount = null,
                        currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                        // 整段上游原文要进 `key_settings.balance_raw`，界面上的"上游说的那句话"
                        // 就是从这里抽出来的（见 [UpstreamMessage]）。原文里有什么是不受我们
                        // 控制的：new-api 系的接口出错时会把请求上下文连**访问令牌**一起回显，
                        // 所以入库前必须过一遍脱敏（红线 32）并截断——原文可能是整页 HTML。
                        raw = rawOf(response.body),
                        checkedAt = now(),
                        error = error.reason,
                    )
                }

                return persist(key, snapshot)
            } finally {
                keySecret?.zeroize()
            }
        } finally {
            token?.zeroize()
        }
    }

    /**
     * 本机在**请求还没发出去**时就得出的失败：写成快照、记一笔账，然后返回它。
     *
     * 为什么不照旧发出去、让上游用 401 告诉我们：那样 `balanceError` 存的是 `http 401`，
     * 界面上"令牌在中转站失效了"和"这台设备解不开自己加密的令牌"就成了同一句话，
     * 而这两件事用户能做的完全不同（前者去站点重签一个令牌，后者要重新导入备份）。
     * 顺带省掉一发注定被拒的请求。
     */
    private suspend fun recordLocalFailure(key: ApiKey, reason: String): BalanceSnapshot {
        val failed = BalanceSnapshot(
            amount = null,
            currency = BalanceSnapshot.UNKNOWN_CURRENCY,
            checkedAt = now(),
            error = reason,
        )
        return persist(key, failed)
    }

    /**
     * 一把 Key 的结果落库，顺带记历史与日志；返回**真正写进库里的那一份**。
     *
     * 三发各自挡异常：`AuditLogRepository.record` 是不防御的，库忙时就抛，而它丢的只是
     * 一行流水，没有任何理由让余额快照写不进去、更没理由让后面每一把 Key 跟着陪葬。
     * 取消仍然要原样抛出去。
     */
    private suspend fun persist(key: ApiKey, snapshot: BalanceSnapshot): BalanceSnapshot {
        // **每一路都要过 `rawOf`**，成功那一路也不例外：适配器给的是上游原文
        // （`NewApiAdapter` 直接 `raw = body`），而 new-api 的 `/api/user/self` 会把
        // `access_token` 连同邮箱一起回显。以前只有解析失败那一路洗过，成功那一路是
        // 明文进 `balance_raw`——那时候这一列没人显示，所以没暴露；现在界面上要抽一句
        // 原因出来（[UpstreamMessage]），存进去的东西就必须是洗过的。
        val stored = snapshot.copy(raw = rawOf(snapshot.raw))
        runQuietly { keys.updateBalance(key.id, stored) }
        // 历史只记「成功且金额变了」的点：record 内部去重，失败快照（amount == null）
        // 会被挡掉，所以这里无条件调用即可，不必再判一次 error。
        runQuietly { history.record(key.providerId, key.id, stored) }
        runQuietly { auditBalance(key.providerId, key.id, stored) }
        return stored
    }

    /** 挡下除取消以外的一切异常：这几发都是"能写成最好，写不成也别挡路"。 */
    private suspend inline fun runQuietly(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }

    /**
     * 上游原文进库前的处理：**截断 + 脱敏**。
     *
     * 脱敏器在这里现造而不是从 DI 传：`Redactor` 本身无状态，它的"记忆"全在
     * [knownSecrets] 那份会话清单里（第一道按已知明文替换），而那份清单已经是单例。
     * 为一个无状态对象再多要一个构造参数，换来的是同一件事有两个可能的来源。
     *
     * 顺序不能倒：先截断再脱敏会让被砍掉的尾巴里那半截令牌漏网，
     * 先脱敏再截断则擦干净了才留下可读的头尾。
     */
    private fun rawOf(body: String?): String? {
        if (body.isNullOrEmpty()) return null
        val scrubbed = redactor.scrub(body)
        return if (scrubbed.length <= MAX_RAW_CHARS) {
            scrubbed
        } else {
            scrubbed.take(MAX_RAW_CHARS) + RAW_TRUNCATED_MARK
        }
    }
    private fun withClientProfile(request: ProbeRequest, profile: ClientProfile?): ProbeRequest =
        request.copy(
            headers = HeaderAssembler.assemble(
                baseHeaders = BASE_HEADERS,
                profile = profile,
                authHeaders = request.headers,
                placeholders = placeholders,
            ).headers,
        )

    private suspend fun tryCalibrate(
        adapter: NewApiAdapter,
        settings: com.lc33.tokenvault.domain.model.KeySettings,
        profile: ClientProfile?,
    ): Double? {
        val base = settings.balanceBaseUrl?.trimEnd('/') ?: settings.apiRoot.trimEnd('/')
        val response = engine.execute(
            withClientProfile(
                ProbeRequest(
                    method = "GET",
                    url = "$base/api/status",
                    headers = emptyList(),
                    protocol = null,
                ),
                profile,
            ),
            allowInsecure = settings.allowInsecure,
        )
        if (response.error != null || response.status !in 200..299) return null
        return adapter.calibrateQuotaPerUnit(response.body)
    }

    private companion object {
        val BASE_HEADERS = listOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
        )

        /**
         * `balance_raw` 的字符上限。上游错误页是一整页 HTML（几百 KB 不奇怪），而这一列
         * 每把 Key 只留最新一份、显示在余额详情的折叠区里——留头 8 KB 足够看出"回的是
         * HTML 而不是 JSON"，整页塞进库只会把表撑大、把详情页卡住。
         */
        const val MAX_RAW_CHARS = 8_000

        /** 截断标记用英文：`balance_raw` 是上游原文那一栏的技术性内容，界面文案才走资源
         *  （与 `net/HttpEngine` 的报文截断标记同一条约定）。 */
        const val RAW_TRUNCATED_MARK = "…[truncated]"
    }

    private suspend fun auditBalance(
        providerId: Long,
        keyId: Long,
        snapshot: BalanceSnapshot,
    ) {
        val level = if (snapshot.error == null) LogLevel.INFO else LogLevel.ERROR
        audit.record(
            level = level,
            category = LogCategory.BALANCE,
            message = if (snapshot.error == null) "balance refreshed" else "balance refresh failed",
            detail = snapshot.error
                // 日志里也要带上游原话：只有 `balance_raw` 抽得出来，而"日志页"正是用户在
                // 界面上看不到原因时唯一会去的地方。以前那里只有 `http 401`，等于把
                // 唯一有用的那句留在库里不给看。
                ?.let { reason ->
                    UpstreamMessage.of(snapshot.raw)?.let { "$reason: $it" } ?: reason
                }
                ?: snapshot.amount?.let { "$it ${snapshot.currency}" }
                ?: "unknown amount",
            providerId = providerId,
            keyId = keyId,
        )
    }
}

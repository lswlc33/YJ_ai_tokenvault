package com.lc33.tokenvault.engine

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
import com.lc33.tokenvault.net.HttpEngine
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
    suspend fun refreshAll(): Int {
        val allKeys = keys.observeAll().first()
        var refreshed = 0
        for (key in allKeys.filter { it.settings.probe.enabled && it.settings.probe.balance }) {
            if (key.settings.balanceKind == BalanceKind.NONE) continue
            if (refreshKey(key) != null) refreshed++
        }
        return refreshed
    }

    suspend fun refresh(providerId: Long, keyId: Long? = null): List<BalanceSnapshot> {
        val provider = providers.find(providerId) ?: return emptyList()
        val selected = keys.observeByProvider(providerId).first()
            .filter { keyId == null || it.id == keyId }
        return selected.mapNotNull { key ->
            if (!key.settings.probe.enabled || !key.settings.probe.balance) return@mapNotNull null
            refreshKey(key)
        }
    }

    private suspend fun refreshKey(key: ApiKey): BalanceSnapshot? {
        val settings = key.settings
        var effectiveSettings = settings
        var adapter = BalanceRegistry.forSettings(effectiveSettings) ?: return null

        val profileList = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        val profile = profileList.firstOrNull { it.id == settings.clientProfileId } ?: defaultProfile

        val token = if (adapter.kind.usesOwnToken) {
            try {
                keys.revealBalanceToken(key.id)
            } catch (_: Exception) {
                null
            }?.also { knownSecrets.add(it) }
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
                revealKey(key.id)?.also { knownSecrets.add(it) }
            }
            try {
                // `buildRequest` 也会抛 `BalanceParseException`（`CustomJsonAdapter` 在 headers
                // 被配成对象/数组时抛 bad_headers_config / bad_header_value_*）。它以前落在
                // 下面那个只管 `parse` 的 catch 之外：异常一路冲出 `refreshAll` 的 for 循环，
                // **后面每一把 Key 都不再刷余额**，而四个调用点全是 `runCatching` —— 用户看到的
                // 就是"余额永远不动、零提示"。这里把它收成这一把 Key 的失败快照。
                val request = try {
                    withClientProfile(
                        adapter.buildRequest(effectiveSettings, keySecret, token),
                        profile,
                    )
                } catch (error: BalanceParseException) {
                    val failed = BalanceSnapshot(
                        amount = null,
                        currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                        checkedAt = now(),
                        error = error.reason,
                    )
                    keys.updateBalance(key.id, failed)
                    auditBalance(key.providerId, key.id, failed)
                    return failed
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
                        // 整段上游原文要进 `key_settings.balance_raw`、并且显示在余额详情的
                        // 折叠区里（用户拿它跟上游页面对账）。原文里有什么是不受我们控制的：
                        // new-api 系的接口出错时会把请求上下文连**访问令牌**一起回显，
                        // 所以入库前必须过一遍脱敏（红线 32）并截断——原文可能是整页 HTML。
                        raw = rawOf(response.body),
                        checkedAt = now(),
                        error = error.reason,
                    )
                }

                keys.updateBalance(key.id, snapshot)
                // 历史只记「成功且金额变了」的点：record 内部去重，失败快照（amount == null）
                // 会被挡掉，所以这里无条件调用即可，不必再判一次 error。
                history.record(key.providerId, key.id, snapshot)
                auditBalance(key.providerId, key.id, snapshot)
                return snapshot
            } finally {
                keySecret?.zeroize()
            }
        } finally {
            token?.zeroize()
        }
    }

    private suspend fun revealKey(keyId: Long): CharArray? = try {
        keys.reveal(keyId)
    } catch (_: Exception) {
        null
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
                ?: snapshot.amount?.let { "$it ${snapshot.currency}" }
                ?: "unknown amount",
            providerId = providerId,
            keyId = keyId,
        )
    }
}

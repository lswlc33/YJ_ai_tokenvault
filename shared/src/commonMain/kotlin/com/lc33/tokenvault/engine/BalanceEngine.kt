package com.lc33.tokenvault.engine

import com.lc33.tokenvault.balance.BalanceParseException
import com.lc33.tokenvault.balance.BalanceRegistry
import com.lc33.tokenvault.balance.NewApiAdapter
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ClientProfileRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.endpoint.HeaderAssembler
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.net.HttpEngine
import kotlinx.coroutines.flow.first

/**
 * 余额查询引擎（§9）。
 *
 * 余额快照落在 `api_keys` 上：同一供应商的不同 Key 可能对应不同账户 / 不同额度，
 * 供应商卡片上的金额由 UI 对这批 Key 求和。newapi 这类使用独立访问令牌的适配器查到
 * 的是站点账户余额，`refreshAll` 只把它挂到默认 Key，避免多张 Key 把同一个账户余额
 * 重复相加。
 */
class BalanceEngine constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val clientProfiles: ClientProfileRepository,
    private val engine: HttpEngine,
    private val audit: AuditLogRepository,
    private val now: () -> Long,
    private val placeholders: Map<String, String>,
) {

    /** 刷新所有已开启余额探测的供应商。返回实际发起查询的供应商数。 */
    suspend fun refreshAll(): Int {
        val summaries = providers.observeSummaries().first()
        var refreshed = 0
        for (summary in summaries) {
            val provider = summary.provider
            if (!provider.probe.enabled || !provider.probe.balance) continue
            if (provider.balanceKind == BalanceKind.NONE) continue
            if (runCatching { refresh(provider.id) }.getOrDefault(emptyList()).isNotEmpty()) refreshed++
        }
        return refreshed
    }

    /**
     * 查一家。返回各 Key 的快照；空列表表示没配置、被供应商开关挡住或没有可查的 Key。
     *
     * @param keyId 指定时只查这一张；null 时按适配器语义选择全部 Key（API Key 型）
     * 或默认 Key（独立访问令牌型）。
     */
    suspend fun refresh(providerId: Long, keyId: Long? = null): List<BalanceSnapshot> {
        val provider = providers.find(providerId) ?: return emptyList()
        if (!provider.probe.enabled || !provider.probe.balance) return emptyList()
        val adapter = BalanceRegistry.forProvider(provider) ?: return emptyList()

        val allKeys = keys.observeByProvider(providerId).first().filter { it.enabled }
        val selectedKeys = when {
            keyId != null -> allKeys.filter { it.id == keyId }
            adapter.kind.usesOwnToken -> allKeys.filter { it.isDefault }
            else -> allKeys
        }
        if (selectedKeys.isEmpty()) return emptyList()

        val token = if (adapter.kind.usesOwnToken) providers.revealBalanceToken(providerId) else null
        val profileList = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        val profile = profileList.firstOrNull { it.id == provider.clientProfileId } ?: defaultProfile

        try {
            if (adapter is NewApiAdapter) {
                val calibratedValue = tryCalibrate(adapter, provider, profile)
                if (calibratedValue != null) {
                    providers.calibrateQuotaPerUnit(providerId, calibratedValue)
                }
            }

            val snapshots = mutableListOf<BalanceSnapshot>()
            for (key in selectedKeys) {
                val keySecret = if (adapter.kind.usesOwnToken) null else revealKey(key.id)
                try {
                    val request = withClientProfile(
                        adapter.buildRequest(provider, keySecret, token),
                        profile,
                    )
                    val response = engine.execute(request, allowInsecure = provider.allowInsecure)
                    val snapshot = response.error?.let { err ->
                        BalanceSnapshot(
                            amount = null,
                            currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                            checkedAt = now(),
                            error = err.message ?: "network error",
                        )
                    } ?: try {
                        adapter.parse(response.status, response.body).copy(checkedAt = now())
                    } catch (e: BalanceParseException) {
                        BalanceSnapshot(
                            amount = null,
                            currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                            raw = response.body,
                            checkedAt = now(),
                            error = e.reason,
                        )
                    }

                    keys.updateBalance(key.id, snapshot)
                    snapshots += snapshot
                    auditBalance(providerId, key.id, snapshot)
                } finally {
                    keySecret?.zeroize()
                }
            }
            return snapshots
        } finally {
            token?.zeroize()
        }
    }

    private suspend fun revealKey(keyId: Long): CharArray? = try {
        keys.reveal(keyId)
    } catch (_: Exception) {
        null
    }

    /** 余额请求同样要过客户端预设：有些中转站会按 UA / 特征头拦截余额接口。 */
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
        provider: Provider,
        profile: ClientProfile?,
    ): Double? {
        val base = provider.balanceBaseUrl?.trimEnd('/') ?: provider.apiRoot.trimEnd('/')
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
            allowInsecure = provider.allowInsecure,
        )
        if (response.error != null || response.status !in 200..299) return null
        return adapter.calibrateQuotaPerUnit(response.body)
    }

    private companion object {
        val BASE_HEADERS = listOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
        )
    }

    private suspend fun auditBalance(
        providerId: Long,
        keyId: Long,
        snapshot: BalanceSnapshot,
    ) {
        if (snapshot.error != null) {
            audit.record(
                level = LogLevel.ERROR,
                category = LogCategory.BALANCE,
                message = "balance refresh failed",
                detail = snapshot.error,
                providerId = providerId,
                keyId = keyId,
            )
        } else {
            audit.record(
                level = LogLevel.INFO,
                category = LogCategory.BALANCE,
                message = "balance refreshed",
                detail = snapshot.amount?.let { "$it ${snapshot.currency}" } ?: "unknown amount",
                providerId = providerId,
                keyId = keyId,
            )
        }
    }
}
package com.lc33.tokenvault.engine

import com.lc33.tokenvault.balance.BalanceParseException
import com.lc33.tokenvault.balance.BalanceRegistry
import com.lc33.tokenvault.balance.NewApiAdapter
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.di.NowEpochMs
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import com.lc33.tokenvault.domain.repo.ProviderRepository
import com.lc33.tokenvault.endpoint.ProbeRequest
import com.lc33.tokenvault.net.HttpEngine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 余额查询引擎（§9）。
 *
 * 与 [ProbeEngine] 分开：探测是"一轮、逐项、可取消、有进度"，余额是"单家、点一下、查一次"，
 * 两者的生命周期与取消语义不同，揉在一起只会互相拖累。
 *
 * 一条链：选适配器 → 解出鉴权材料（令牌或默认 Key）→ 构造请求 → 执行 → 解析 → 落库。
 * 失败（网络 / 解析）也落库——落一条 `error`，让 UI 能区分"查询失败"与"余额为 0"（§9.3）。
 *
 * newapi 的特殊之处：解析前先试 `/api/status` 校准 `quotaPerUnit`。校准失败不致命——
 * 用默认 500000 继续，但 `quotaCalibrated` 保持 0，UI 据此标"换算比未校准"（§9.2）。
 */
@Singleton
class BalanceEngine @Inject constructor(
    private val providers: ProviderRepository,
    private val keys: ApiKeyRepository,
    private val engine: HttpEngine,
    private val audit: AuditLogRepository,
    @param:NowEpochMs private val now: () -> Long,
) {

    /** 查一家。返回落库后的快照；`balanceKind = none` 返回 null（不是错误）。 */
    suspend fun refresh(providerId: Long): BalanceSnapshot? {
        val provider = providers.find(providerId) ?: return null
        val adapter = BalanceRegistry.forProvider(provider) ?: return null

        // 解出鉴权材料。红线 1：明文最短存活，用完全部擦掉。
        val token = if (adapter.kind.usesOwnToken) providers.revealBalanceToken(providerId) else null
        val defaultKey = if (!adapter.kind.usesOwnToken) revealDefaultKey(providerId) else null

        try {
            // newapi 先校准换算比。
            var calibrated = provider.quotaCalibrated
            if (adapter is NewApiAdapter) {
                val calibratedValue = tryCalibrate(adapter, provider)
                if (calibratedValue != null) {
                    providers.calibrateQuotaPerUnit(providerId, calibratedValue)
                    calibrated = true
                }
            }

            val request = adapter.buildRequest(provider, defaultKey, token)
            val response = engine.execute(request, allowInsecure = provider.allowInsecure)

            val snapshot = response.error?.let { err ->
                // 网络层失败：落一条 error，而不是 amount=null 还当成功。
                BalanceSnapshot(
                    amount = null,
                    currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                    checkedAt = now(),
                    error = err.message ?: "network error",
                )
            } ?: try {
                adapter.parse(response.status, response.body).copy(checkedAt = now())
            } catch (e: BalanceParseException) {
                // 解析失败：落 error（红线 8 的邻居：不静默降级）。原始 body 只进 raw，
                // 不进 error 消息（红线 32）。
                BalanceSnapshot(
                    amount = null,
                    currency = BalanceSnapshot.UNKNOWN_CURRENCY,
                    raw = response.body,
                    checkedAt = now(),
                    error = e.reason,
                )
            }

            providers.updateBalance(providerId, snapshot, calibrated)

            // 余额查询结果进日志：成功给 INFO（含金额），失败给 ERROR（含脱敏后的 error）。
            if (snapshot.error != null) {
                audit.record(
                    level = LogLevel.ERROR,
                    category = LogCategory.BALANCE,
                    message = "balance refresh failed",
                    detail = snapshot.error,
                    providerId = providerId,
                )
            } else {
                audit.record(
                    level = LogLevel.INFO,
                    category = LogCategory.BALANCE,
                    message = "balance refreshed",
                    detail = snapshot.amount?.let { "$it ${snapshot.currency}" } ?: "unknown amount",
                    providerId = providerId,
                )
            }
            return snapshot
        } finally {
            token?.zeroize()
            defaultKey?.zeroize()
        }
    }

    /** 解出该供应商的默认 Key（复用默认 Key 的适配器用）。没有默认 Key 返回 null。 */
    private suspend fun revealDefaultKey(providerId: Long): CharArray? {
        val defaultKey = keys.observeByProvider(providerId).first().firstOrNull { it.isDefault && it.enabled }
            ?: return null
        return try {
            keys.reveal(defaultKey.id)
        } catch (_: Exception) {
            null
        }
    }

    /** 试读 `/api/status` 的 `quota_per_unit`。任何失败返回 null（用默认值继续）。 */
    private suspend fun tryCalibrate(adapter: NewApiAdapter, provider: Provider): Double? {
        val base = provider.balanceBaseUrl?.trimEnd('/') ?: provider.apiRoot.trimEnd('/')
        val response = engine.execute(
            ProbeRequest(
                method = "GET",
                url = "$base/api/status",
                headers = emptyList(),
                protocol = null,
            ),
            allowInsecure = provider.allowInsecure,
        )
        if (response.error != null || response.status !in 200..299) return null
        return adapter.calibrateQuotaPerUnit(response.body)
    }
}

package com.lc33.tokenvault.engine

import com.lc33.tokenvault.balance.BalanceParseException
import com.lc33.tokenvault.balance.BalanceRegistry
import com.lc33.tokenvault.balance.NewApiAdapter
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.repo.ApiKeyRepository
import com.lc33.tokenvault.domain.repo.AuditLogRepository
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
    private val now: () -> Long,
    private val placeholders: Map<String, String>,
) {
    suspend fun refreshAll(): Int {
        val allKeys = keys.observeAll().first()
        var refreshed = 0
        for (key in allKeys.filter { it.enabled && it.settings.probe.enabled && it.settings.probe.balance }) {
            if (key.settings.balanceKind == BalanceKind.NONE) continue
            if (refreshKey(key) != null) refreshed++
        }
        return refreshed
    }

    suspend fun refresh(providerId: Long, keyId: Long? = null): List<BalanceSnapshot> {
        val provider = providers.find(providerId) ?: return emptyList()
        val selected = keys.observeByProvider(providerId).first()
            .filter { it.enabled && (keyId == null || it.id == keyId) }
        return selected.mapNotNull { key ->
            if (!key.settings.probe.enabled || !key.settings.probe.balance) return@mapNotNull null
            refreshKey(key)
        }
    }

    private suspend fun refreshKey(key: ApiKey): BalanceSnapshot? {
        val settings = key.settings
        val adapter = BalanceRegistry.forSettings(settings) ?: return null

        val profileList = clientProfiles.observeAll().first()
        val defaultProfile = profileList.firstOrNull { it.builtinKey == "default" }
        val profile = profileList.firstOrNull { it.id == settings.clientProfileId } ?: defaultProfile

        val token = if (adapter.kind.usesOwnToken) {
            try {
                keys.revealBalanceToken(key.id)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        try {
            if (adapter is NewApiAdapter) {
                val calibrated = tryCalibrate(adapter, settings, profile)
                if (calibrated != null) {
                    keys.updateSettings(
                        id = key.id,
                        settings = settings.copy(quotaPerUnit = calibrated, quotaCalibrated = true),
                    )
                }
            }

            val keySecret = if (adapter.kind.usesOwnToken) null else revealKey(key.id)
            try {
                val request = withClientProfile(
                    adapter.buildRequest(settings, keySecret, token),
                    profile,
                )
                val response = engine.execute(request, allowInsecure = settings.allowInsecure)
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
                        raw = response.body,
                        checkedAt = now(),
                        error = error.reason,
                    )
                }

                keys.updateBalance(key.id, snapshot)
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

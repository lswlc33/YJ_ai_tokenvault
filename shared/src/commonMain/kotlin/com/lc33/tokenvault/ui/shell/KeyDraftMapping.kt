package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.KeyProbeSettings
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.endpoint.normalizeBaseUrl
import com.lc33.tokenvault.screens.model.KeyDraft

val KEY_BALANCE_KINDS: List<BalanceKind> = listOf(
    BalanceKind.NONE,
    BalanceKind.NEWAPI,
    BalanceKind.DEEPSEEK,
    BalanceKind.OPENROUTER,
    BalanceKind.SILICONFLOW,
    BalanceKind.MOONSHOT,
    BalanceKind.CUSTOM_JSON,
)

val KEY_AUTH_STYLES: List<AuthStyle> = listOf(AuthStyle.AUTO, AuthStyle.BEARER, AuthStyle.X_API_KEY)

fun ApiKey.toDraft(profiles: List<ClientProfile>): KeyDraft = KeyDraft(
    id = id,
    providerId = providerId,
    label = label,
    note = note,
    enabled = enabled,
    sortOrder = sortOrder,
    baseUrl = settings.apiBaseUrl,
    protocols = settings.supportedProtocols,
    authStyleIndex = KEY_AUTH_STYLES.indexOf(settings.authStyle).coerceAtLeast(0),
    profileIndex = profileIndexOf(settings.clientProfileId, profiles),
    pathOverrideAnthropic = settings.pathOverrides[Protocol.ANTHROPIC].orEmpty(),
    timeoutSeconds = settings.timeoutSeconds?.toString().orEmpty(),
    allowInsecure = settings.allowInsecure,
    balanceKindIndex = KEY_BALANCE_KINDS.indexOf(settings.balanceKind).coerceAtLeast(0),
    balanceUserId = settings.balanceUserId.orEmpty(),
    probeEnabled = settings.probe.enabled,
    probeReachability = settings.probe.reachability,
    probeKeys = settings.probe.keyValidity,
    probeBalance = settings.probe.balance,
    probeModels = settings.probe.models,
    probeModelReachability = settings.probe.modelReachability,
)

fun KeyDraft.toSettings(
    existing: KeySettings?,
    normalized: NormalizeResult.Ok,
    profiles: List<ClientProfile>,
): KeySettings {
    val base = existing ?: KeySettings(apiBaseUrl = baseUrl, apiRoot = normalized.endpoints.apiRoot)
    return base.copy(
        apiBaseUrl = baseUrl,
        apiRoot = normalized.endpoints.apiRoot,
        apiVersion = normalized.endpoints.ver,
        supportedProtocols = protocols,
        pathOverrides = if (pathOverrideAnthropic.isBlank()) {
            emptyMap()
        } else {
            mapOf(Protocol.ANTHROPIC to pathOverrideAnthropic)
        },
        authStyle = KEY_AUTH_STYLES.getOrElse(authStyleIndex) { AuthStyle.AUTO },
        allowInsecure = allowInsecure,
        clientProfileId = when {
            profileIndex == 0 -> null
            else -> profiles.getOrNull(profileIndex - 1)?.id ?: existing?.clientProfileId
        },
        timeoutSeconds = timeoutSeconds.trim().toIntOrNull(),
        balanceKind = KEY_BALANCE_KINDS.getOrElse(balanceKindIndex) { BalanceKind.NONE },
        balanceUserId = balanceUserId.ifBlank { null },
        probe = KeyProbeSettings(
            enabled = probeEnabled,
            reachability = probeReachability,
            keyValidity = probeKeys,
            balance = probeBalance,
            models = probeModels,
            modelReachability = probeModelReachability,
        ),
    )
}

private fun profileIndexOf(clientProfileId: Long?, profiles: List<ClientProfile>): Int {
    if (clientProfileId == null) return 0
    val index = profiles.indexOfFirst { it.id == clientProfileId }
    return if (index < 0) 0 else index + 1
}

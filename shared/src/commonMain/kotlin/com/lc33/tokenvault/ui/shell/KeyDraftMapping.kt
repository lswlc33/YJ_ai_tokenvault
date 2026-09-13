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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    balanceMethod = settings.balanceConfig.jsonString("method").ifBlank { "GET" },
    balancePath = settings.balanceConfig.jsonString("path"),
    balanceValuePath = settings.balanceConfig.jsonString("valuePath"),
    balanceUsedPath = settings.balanceConfig.jsonString("usedPath"),
    balanceCurrency = settings.balanceConfig.jsonString("currency"),
    probeEnabled = settings.probe.enabled,
    probeReachability = settings.probe.reachability,
    probeKeys = settings.probe.keyValidity,
    probeBalance = settings.probe.balance,
    probeModels = settings.probe.models,
    probeModelReachability = settings.probe.modelReachability,
    probeQuickModel = settings.probe.quickModelProbe,
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
        balanceConfig = if (KEY_BALANCE_KINDS.getOrElse(balanceKindIndex) { BalanceKind.NONE } == BalanceKind.CUSTOM_JSON) {
            buildJsonObject {
                put("method", balanceMethod.ifBlank { "GET" }.uppercase())
                put("path", balancePath.trim())
                put("valuePath", balanceValuePath.trim())
                put("usedPath", balanceUsedPath.trim())
                put("currency", balanceCurrency.trim())
            }.toString()
        } else {
            existing?.balanceConfig ?: "{}"
        },
        probe = KeyProbeSettings(
            enabled = probeEnabled,
            reachability = probeReachability,
            keyValidity = probeKeys,
            balance = probeBalance,
            models = probeModels,
            modelReachability = probeModelReachability,
            quickModelProbe = probeQuickModel,
        ),
    )
}

private fun profileIndexOf(clientProfileId: Long?, profiles: List<ClientProfile>): Int {
    if (clientProfileId == null) return 0
    val index = profiles.indexOfFirst { it.id == clientProfileId }
    return if (index < 0) 0 else index + 1
}

private val balanceJson = Json { ignoreUnknownKeys = true }

private fun String.jsonString(key: String): String = runCatching {
    balanceJson.parseToJsonElement(this).jsonObject[key]?.jsonPrimitive?.content
}.getOrNull().orEmpty()

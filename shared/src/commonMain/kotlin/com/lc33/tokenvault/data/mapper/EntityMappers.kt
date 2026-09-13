package com.lc33.tokenvault.data.mapper

import com.lc33.tokenvault.data.dao.ApiKeyWithSettingsRow
import com.lc33.tokenvault.data.dao.ProviderSummaryRow
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.LoginMethod
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.KeyProbeSettings
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderAccount
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.domain.model.WebsiteStatus

// ------------------------------------------------------------------ groups

fun GroupEntity.toDomain(): Group = Group(id = id, name = name, sortOrder = sortOrder)

fun Group.toEntity(): GroupEntity = GroupEntity(id = id, name = name, sortOrder = sortOrder)

// ------------------------------------------------------------------ providers

fun ProviderEntity.toDomain(): Provider = Provider(
    id = id,
    name = name,
    note = note,
    websiteUrl = websiteUrl,
    website = WebsiteStatus(
        latencyMs = websiteLatencyMs,
        checkedAt = websiteCheckedAt,
        error = websiteError,
    ),
    groupId = groupId,
    color = color,
    pinned = pinned,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Provider.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    name = name,
    note = note,
    websiteUrl = websiteUrl,
    websiteLatencyMs = website.latencyMs,
    websiteCheckedAt = website.checkedAt,
    websiteError = website.error,
    groupId = groupId,
    color = color,
    pinned = pinned,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ProviderSummaryRow.toDomain(): ProviderSummary = ProviderSummary(
    provider = provider.toDomain(),
    keyCount = keyCount,
    okKeyCount = okKeyCount,
    modelCount = modelCount,
    accountCount = accountCount,
)

// ------------------------------------------------------------------ key_settings

fun KeySettingsEntity.toDomain(): KeySettings = KeySettings(
    apiBaseUrl = apiBaseUrl,
    apiRoot = apiRoot,
    apiVersion = apiVersion,
    supportedProtocols = supportedProtocols.toProtocolSet(),
    pathOverrides = pathOverrides.toPathOverrides(),
    authStyle = AuthStyle.fromWireName(authStyle),
    allowInsecure = allowInsecure,
    clientProfileId = clientProfileId,
    timeoutSeconds = timeoutSeconds,
    balanceKind = BalanceKind.fromWireName(balanceKind),
    balanceBaseUrl = balanceBaseUrl,
    balanceUserId = balanceUserId,
    balanceTokenEnc = balanceTokenEnc,
    balanceConfig = balanceConfig,
    quotaPerUnit = quotaPerUnit,
    quotaCalibrated = quotaCalibrated,
    probe = KeyProbeSettings(
        enabled = probeEnabled,
        reachability = probeReachability,
        keyValidity = probeKeyValidity,
        balance = probeBalance,
        models = probeModels,
        modelReachability = probeModelReachability,
        quickModelProbe = probeQuickModel,
    ),
)

fun KeySettings.toEntity(keyId: Long, updatedAt: Long): KeySettingsEntity = KeySettingsEntity(
    keyId = keyId,
    apiBaseUrl = apiBaseUrl,
    apiRoot = apiRoot,
    apiVersion = apiVersion,
    supportedProtocols = supportedProtocols.toCsv(),
    pathOverrides = pathOverrides.pathOverridesToJson(),
    authStyle = authStyle.wireName,
    allowInsecure = allowInsecure,
    clientProfileId = clientProfileId,
    timeoutSeconds = timeoutSeconds,
    balanceKind = balanceKind.wireName,
    balanceBaseUrl = balanceBaseUrl,
    balanceUserId = balanceUserId,
    balanceTokenEnc = balanceTokenEnc,
    balanceConfig = balanceConfig,
    quotaPerUnit = quotaPerUnit,
    quotaCalibrated = quotaCalibrated,
    probeEnabled = probe.enabled,
    probeReachability = probe.reachability,
    probeKeyValidity = probe.keyValidity,
    probeBalance = probe.balance,
    probeModels = probe.models,
    probeModelReachability = probe.modelReachability,
    probeQuickModel = probe.quickModelProbe,
    updatedAt = updatedAt,
)

// ------------------------------------------------------------------ api_keys

private fun ApiKeyEntity.balanceSnapshot(): BalanceSnapshot? {
    if (balanceAmount == null &&
        balanceUsed == null &&
        balanceRaw == null &&
        balanceCheckedAt == null &&
        balanceError == null
    ) {
        return null
    }
    return BalanceSnapshot(
        amount = balanceAmount,
        used = balanceUsed,
        currency = balanceCurrency ?: BalanceSnapshot.UNKNOWN_CURRENCY,
        raw = balanceRaw,
        checkedAt = balanceCheckedAt,
        error = balanceError,
    )
}

fun ApiKeyWithSettingsRow.toDomain(): ApiKey {
    val settingsEntity = settings
        ?: KeySettingsEntity(
            keyId = key.id,
            apiBaseUrl = "",
            apiRoot = "",
            updatedAt = key.updatedAt,
        )
    return ApiKey(
        id = key.id,
        providerId = key.providerId,
        label = key.label,
        note = key.note,
        secretEnc = key.secretEnc,
        fingerprint = key.fingerprint,
        enabled = key.enabled,
        settings = settingsEntity.toDomain(),
        health = KeyHealth.fromWireName(key.health),
        lastOutcome = ProbeOutcome.fromWireName(key.lastOutcome),
        healthDetail = key.healthDetail,
        httpStatus = key.httpStatus,
        latencyMs = key.latencyMs,
        checkedAt = key.checkedAt,
        okAt = key.okAt,
        balance = key.balanceSnapshot(),
        sortOrder = key.sortOrder,
        createdAt = key.createdAt,
        updatedAt = key.updatedAt,
    )
}

fun ApiKey.toEntity(): ApiKeyEntity = ApiKeyEntity(
    id = id,
    providerId = providerId,
    label = label,
    note = note,
    secretEnc = secretEnc,
    fingerprint = fingerprint,
    enabled = enabled,
    health = health.wireName,
    lastOutcome = lastOutcome.wireName,
    healthDetail = healthDetail,
    httpStatus = httpStatus,
    latencyMs = latencyMs,
    checkedAt = checkedAt,
    okAt = okAt,
    balanceAmount = balance?.amount,
    balanceUsed = balance?.used,
    balanceCurrency = balance?.currency,
    balanceRaw = balance?.raw,
    balanceCheckedAt = balance?.checkedAt,
    balanceError = balance?.error,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ------------------------------------------------------------------ provider_accounts

fun ProviderAccountEntity.toDomain(): ProviderAccount = ProviderAccount(
    id = id,
    providerId = providerId,
    label = label,
    usernameEnc = usernameEnc,
    usernameFp = usernameFp,
    passwordEnc = passwordEnc,
    loginUrl = loginUrl,
    loginMethods = loginMethods.toLoginMethodSet(),
    note = note,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ProviderAccount.toEntity(): ProviderAccountEntity = ProviderAccountEntity(
    id = id,
    providerId = providerId,
    label = label,
    usernameEnc = usernameEnc,
    usernameFp = usernameFp,
    passwordEnc = passwordEnc,
    loginUrl = loginUrl,
    loginMethods = loginMethods.toLoginMethodsCsv(),
    note = note,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ------------------------------------------------------------------ models

fun ModelEntity.toDomain(): AiModel = AiModel(
    id = id,
    providerId = providerId,
    keyId = keyId,
    modelId = modelId,
    protocol = Protocol.fromWireName(protocol) ?: Protocol.CHAT,
    displayName = displayName,
    source = ModelSource.fromWireName(source),
    discoveredVia = discoveredVia?.let { Protocol.fromWireName(it) },
    enabled = enabled,
    favorite = favorite,
    needsReview = needsReview,
    catalogKey = catalogKey,
    probeState = ModelProbeState.fromWireName(probeState),
    lastOutcome = ProbeOutcome.fromWireName(lastOutcome),
    probeDetail = probeDetail,
    latencyMs = latencyMs,
    probedAt = probedAt,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    sortOrder = sortOrder,
)

fun AiModel.toEntity(): ModelEntity = ModelEntity(
    id = id,
    providerId = providerId,
    keyId = keyId,
    modelId = modelId,
    protocol = protocol.wireName,
    displayName = displayName,
    source = source.wireName,
    discoveredVia = discoveredVia?.wireName,
    enabled = enabled,
    favorite = favorite,
    needsReview = needsReview,
    catalogKey = catalogKey,
    probeState = probeState.wireName,
    lastOutcome = lastOutcome.wireName,
    probeDetail = probeDetail,
    latencyMs = latencyMs,
    probedAt = probedAt,
    firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt,
    sortOrder = sortOrder,
)

// ------------------------------------------------------------------ client_profiles

fun ClientProfileEntity.toDomain(): ClientProfile = ClientProfile(
    id = id,
    name = name,
    builtinKey = builtinKey,
    userAgent = userAgent,
    headers = headers.toHeaderList(),
    bodyPatch = bodyPatch,
    protocols = protocols.toProtocolSet(),
    verified = verified,
    builtinRev = builtinRev,
    userEdited = userEdited,
    sortOrder = sortOrder,
)

fun ClientProfile.toEntity(): ClientProfileEntity = ClientProfileEntity(
    id = id,
    name = name,
    builtinKey = builtinKey,
    userAgent = userAgent,
    headers = headers.headersToJson(),
    bodyPatch = bodyPatch,
    protocols = protocols.toCsv(),
    verified = verified,
    builtinRev = builtinRev,
    userEdited = userEdited,
    sortOrder = sortOrder,
)

// ------------------------------------------------------------------ audit_log

fun AuditLogEntity.toDomain(): AuditEntry = AuditEntry(
    id = id,
    at = at,
    level = LogLevel.fromWireName(level),
    category = LogCategory.fromWireName(category),
    providerId = providerId,
    keyId = keyId,
    message = message,
    detail = detail,
)

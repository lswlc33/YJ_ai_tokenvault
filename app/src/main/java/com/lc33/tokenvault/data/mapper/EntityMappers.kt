package com.lc33.tokenvault.data.mapper

import com.lc33.tokenvault.data.dao.ProviderSummaryRow
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.ClientProfile
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderAccount
import com.lc33.tokenvault.domain.model.ProviderProbeSettings
import com.lc33.tokenvault.domain.model.ProviderSummary

/**
 * Room 实体 ↔ 领域模型。
 *
 * CLAUDE.md 要求这两套类分开、中间有**显式**映射器，这里就是那个映射器。列级的
 * CSV / JSON 编解码在 [ColumnCodecs] 里，这一层只做"哪个字段对哪个字段"。
 *
 * 三条规矩：
 *
 * 1. **密文列只搬字节，绝不解密**（§6.1 推论 3）。`secretEnc` / `usernameEnc` /
 *    `passwordEnc` / `balanceTokenEnc` 进来是 `ByteArray`、出去还是 `ByteArray`。
 *    在这里解密的表现很具体：列表页订阅的 `Flow` 会在锁定那一瞬间从内部抛异常，
 *    整条订阅断掉，用户看到的是"数据全没了"。
 * 2. **读方向单向容错**：认不出来的枚举值取默认档而不是抛（每个 `fromWireName` 都这么写）。
 *    一行坏数据不该让整页打不开。写方向不容错——写进去的一定是我们认识的值。
 *    代价要说清楚：坏值被读成默认档之后，**再存一次就把原值覆盖掉了**，所以只有
 *    确实要保存那一行时才写回，不做"读出来顺手规范化再写回"这种事。
 * 3. **派生值不参与映射**：`BalanceState` 由阈值现算（§5.3），`ProbeRun.skippedCount`
 *    是 `total - done`。存进去就会有第二个真相。
 */

// ------------------------------------------------------------------ groups

fun GroupEntity.toDomain(): Group = Group(id = id, name = name, sortOrder = sortOrder)

fun Group.toEntity(): GroupEntity = GroupEntity(id = id, name = name, sortOrder = sortOrder)

// ------------------------------------------------------------------ providers

/**
 * 余额那几列 → [BalanceSnapshot]。
 *
 * **全都是 null 时返回 null**，而不是返回一个"金额未知"的快照：这两件事在 UI 上不一样，
 * 一个是"没配置余额查询"，另一个是"配了但还没查过"。而 `balanceError` 单独一列的理由是
 * 红线 14 的邻居——查询失败必须与"余额为 0"可区分（§9.3）。
 */
private fun ProviderEntity.balanceSnapshot(): BalanceSnapshot? {
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

fun ProviderEntity.toDomain(): Provider = Provider(
    id = id,
    name = name,
    note = note,
    websiteUrl = websiteUrl,
    apiBaseUrl = apiBaseUrl,
    apiRoot = apiRoot,
    apiVersion = apiVersion,
    supportedProtocols = supportedProtocols.toProtocolSet(),
    pathOverrides = pathOverrides.toPathOverrides(),
    authStyle = AuthStyle.fromWireName(authStyle),
    allowInsecure = allowInsecure,
    clientProfileId = clientProfileId,
    groupId = groupId,
    color = color,
    pinned = pinned,
    sortOrder = sortOrder,
    balanceKind = BalanceKind.fromWireName(balanceKind),
    balanceBaseUrl = balanceBaseUrl,
    balanceUserId = balanceUserId,
    balanceTokenEnc = balanceTokenEnc,
    balanceConfig = balanceConfig,
    quotaPerUnit = quotaPerUnit,
    balance = balanceSnapshot(),
    quotaCalibrated = quotaCalibrated,
    timeoutSeconds = timeoutSeconds,
    probe = ProviderProbeSettings(
        enabled = probeEnabled,
        reachability = probeReachability,
        keyValidity = probeKeyValidity,
        balance = probeBalance,
        models = probeModels,
    ),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Provider.toEntity(): ProviderEntity = ProviderEntity(
    id = id,
    name = name,
    note = note,
    websiteUrl = websiteUrl,
    apiBaseUrl = apiBaseUrl,
    apiRoot = apiRoot,
    apiVersion = apiVersion,
    supportedProtocols = supportedProtocols.toCsv(),
    pathOverrides = pathOverrides.pathOverridesToJson(),
    authStyle = authStyle.wireName,
    allowInsecure = allowInsecure,
    clientProfileId = clientProfileId,
    groupId = groupId,
    color = color,
    pinned = pinned,
    sortOrder = sortOrder,
    balanceKind = balanceKind.wireName,
    balanceBaseUrl = balanceBaseUrl,
    balanceUserId = balanceUserId,
    balanceTokenEnc = balanceTokenEnc,
    balanceConfig = balanceConfig,
    quotaPerUnit = quotaPerUnit,
    balanceAmount = balance?.amount,
    balanceUsed = balance?.used,
    // 币种只在有快照时写：没查过的行留 null，否则"UNKNOWN"会被当成查过一次的结果
    balanceCurrency = balance?.currency,
    balanceRaw = balance?.raw,
    balanceCheckedAt = balance?.checkedAt,
    balanceError = balance?.error,
    quotaCalibrated = quotaCalibrated,
    timeoutSeconds = timeoutSeconds,
    probeEnabled = probe.enabled,
    probeReachability = probe.reachability,
    probeKeyValidity = probe.keyValidity,
    probeBalance = probe.balance,
    probeModels = probe.models,
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

// ------------------------------------------------------------------ api_keys

fun ApiKeyEntity.toDomain(): ApiKey = ApiKey(
    id = id,
    providerId = providerId,
    label = label,
    secretEnc = secretEnc,
    fingerprint = fingerprint,
    isDefault = isDefault,
    enabled = enabled,
    health = KeyHealth.fromWireName(health),
    lastOutcome = ProbeOutcome.fromWireName(lastOutcome),
    healthDetail = healthDetail,
    httpStatus = httpStatus,
    latencyMs = latencyMs,
    checkedAt = checkedAt,
    okAt = okAt,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ApiKey.toEntity(): ApiKeyEntity = ApiKeyEntity(
    id = id,
    providerId = providerId,
    label = label,
    secretEnc = secretEnc,
    fingerprint = fingerprint,
    isDefault = isDefault,
    enabled = enabled,
    health = health.wireName,
    lastOutcome = lastOutcome.wireName,
    healthDetail = healthDetail,
    httpStatus = httpStatus,
    latencyMs = latencyMs,
    checkedAt = checkedAt,
    okAt = okAt,
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
    note = note,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ------------------------------------------------------------------ models

/**
 * 模型行。
 *
 * `protocol` 认不出来时给 [Protocol.CHAT]：这一列非空，而"这一行属于哪条路径"必须有答案。
 * 猜错的表现是发到错的路径拿 404，可见且可改；跳过整行的表现是模型凭空消失。
 */
fun ModelEntity.toDomain(): AiModel = AiModel(
    id = id,
    providerId = providerId,
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

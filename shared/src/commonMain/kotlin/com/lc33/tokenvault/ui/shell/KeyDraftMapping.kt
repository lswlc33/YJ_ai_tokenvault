package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.DefaultProbeSettings
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

/**
 * 余额适配器的可选项。**不含 [BalanceKind.NONE]**——"不查"由编辑页的
 * 「启用余额查询」开关表达，不在下拉里再给一次。两个手柄管同一个字段，
 * 拨哪个另一个都跟着动，用户看到的是"这里怎么有两处都在管余额"。
 *
 * 顺序必须与 `balance_kinds` 两份 string-array 一一对应（下标即取值），
 * `ArchitectureRulesTest` 会校验数量。
 */
val KEY_BALANCE_KINDS: List<BalanceKind> = listOf(
    BalanceKind.NEWAPI,
    BalanceKind.DEEPSEEK,
    BalanceKind.OPENROUTER,
    BalanceKind.SILICONFLOW,
    BalanceKind.MOONSHOT,
    BalanceKind.VOLCENGINE,
    BalanceKind.CUSTOM_JSON,
)

val KEY_AUTH_STYLES: List<AuthStyle> = listOf(AuthStyle.AUTO, AuthStyle.BEARER, AuthStyle.X_API_KEY)

/**
 * 新建 Key 的草稿初值：探测那几档取自设置页的默认值（[DefaultProbeSettings]）。
 *
 * 抽成纯函数是为了能被测试守住——这段逻辑曾经缺失：编辑页直接 `KeyDraft()`，
 * 于是「探测」设置页里那五个"新建时的默认值"改了什么都不影响，界面在、功能不在。
 *
 * `probeQuickModel` 跟着 `modelReachability` 一起给：编辑页把这两个字段画成一个开关，
 * 而引擎要两个都为真才发快捷探测。只给前者的话，默认打开时开关显示为开、长按却没反应。
 */
fun DefaultProbeSettings.toNewKeyDraft(providerId: Long): KeyDraft = KeyDraft(
    providerId = providerId,
    probeReachability = reachability,
    probeKeys = keys,
    probeBalance = balance,
    probeModels = models,
    probeModelReachability = modelReachability,
    probeQuickModel = modelReachability,
)

fun ApiKey.toDraft(profiles: List<ClientProfile>): KeyDraft = KeyDraft(
    id = id,
    providerId = providerId,
    label = label,
    note = note,
    sortOrder = sortOrder,
    baseUrl = settings.apiBaseUrl,
    protocols = settings.supportedProtocols,
    authStyleIndex = KEY_AUTH_STYLES.indexOf(settings.authStyle).coerceAtLeast(0),
    profileIndex = profileIndexOf(settings.clientProfileId, profiles),
    // 草稿只画 Anthropic 这一格，所以只回显它；其余协议的覆盖不进草稿（也就不会被
    // 顺手改掉），保存时由 [mergedPathOverrides] 原样带回去。
    pathOverrideAnthropic = settings.pathOverrides[Protocol.ANTHROPIC].orEmpty(),
    timeoutSeconds = settings.timeoutSeconds?.toString().orEmpty(),
    allowInsecure = settings.allowInsecure,
    balanceKindIndex = balanceKindIndexOf(settings.balanceKind),
    balanceEnabled = settings.balanceKind != BalanceKind.NONE,
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
        // 路径覆盖**按协议逐项合并**：编辑页只画 Anthropic 这一格，整份重写会把备份恢复
        // 带进来的其它协议覆盖静静地清空（"进一趟编辑页，路径就没了"）。
        pathOverrides = mergedPathOverrides(existing?.pathOverrides, pathOverrideAnthropic),
        authStyle = KEY_AUTH_STYLES.getOrElse(authStyleIndex) { AuthStyle.AUTO },
        allowInsecure = allowInsecure,
        clientProfileId = when {
            profileIndex == 0 -> null
            else -> profiles.getOrNull(profileIndex - 1)?.id ?: existing?.clientProfileId
        },
        timeoutSeconds = timeoutSeconds.trim().toIntOrNull(),
        balanceKind = if (balanceEnabled) {
            KEY_BALANCE_KINDS.getOrElse(balanceKindIndex) { BalanceKind.NEWAPI }
        } else {
            // 开关关掉就是「不查」。适配器选择留在草稿里（下拉仍显示、仍能改），
            // 重新打开时接着用，不用再挑一次。
            BalanceKind.NONE
        },
        balanceUserId = balanceUserId.ifBlank { null },
        balanceConfig = if (effectiveBalanceKind() == BalanceKind.CUSTOM_JSON) {
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

/**
 * 路径覆盖按协议逐项合并：只动 Anthropic 这一项，其余协议（备份恢复带进来的、
 * 或别处写进去的）原样保留。留空即表示这一项不设覆盖，所以把它整条删掉。
 */
private fun mergedPathOverrides(
    existing: Map<Protocol, String>?,
    anthropicOverride: String,
): Map<Protocol, String> {
    val merged = (existing ?: emptyMap())
        .filterKeys { it != Protocol.ANTHROPIC }
        .toMutableMap()
    if (anthropicOverride.isBlank()) merged.remove(Protocol.ANTHROPIC)
    else merged[Protocol.ANTHROPIC] = anthropicOverride.trim()
    return merged
}

/**
 * 落库的余额适配器 → 下拉下标。
 *
 * [BalanceKind.NONE]（以及任何不认识的 kind）落到 0，也就是第一个真实适配器；
 * 它不会因此变成"开着"——开关由 `balanceEnabled` 单独记着，两者一对照就还原得回来。
 */
private fun balanceKindIndexOf(kind: BalanceKind): Int =
    KEY_BALANCE_KINDS.indexOf(kind).takeIf { it >= 0 } ?: 0

/** 草稿里**生效的**适配器：开关关掉时视为无，用于决定要不要写 `balanceConfig`。 */
private fun KeyDraft.effectiveBalanceKind(): BalanceKind = if (balanceEnabled) {
    KEY_BALANCE_KINDS.getOrElse(balanceKindIndex) { BalanceKind.NEWAPI }
} else {
    BalanceKind.NONE
}

private val balanceJson = Json { ignoreUnknownKeys = true }

private fun String.jsonString(key: String): String = runCatching {
    balanceJson.parseToJsonElement(this).jsonObject[key]?.jsonPrimitive?.content
}.getOrNull().orEmpty()

package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderProbeSettings
import com.lc33.tokenvault.endpoint.NormalizeResult
import com.lc33.tokenvault.screens.model.ProviderDraft

/**
 * 编辑页草稿 ↔ 领域对象。
 *
 * 这一对映射是**下标与值之间的边界**：界面上的下拉与色块都按下标选（`AppDropdownRow`
 * 的 API 就是 `selectedIndex`），而库里存的是语义值（`groupId`、`balanceKind.wireName`）。
 * 下标直接入库是错的——重排一次选项，已存数据的含义就全变了（红线 3 的同一条道理）。
 *
 * 两个下标表在这里定义清楚：
 *
 * - **分组**：0 = 「未分组」（`groupId = null`），其余按传入的 `groups` 顺序对齐。
 * - **余额种类**：对应 `R.array.balance_kinds`，顺序与 [BALANCE_KINDS] 一致。
 *   数组与这张表不一致的表现是"选了 newapi 存成 deepseek"，所以
 *   `ArchitectureRulesTest` 里有一条按数量比对。
 */

/**
 * 余额种类下拉的顺序。
 *
 * `NONE` 在第 0 位（默认不查余额），`NEWAPI` 在第 1 位——编辑页用
 * `balanceKindIndex == 1` 判断"要不要显示访问令牌那两格"，那个判断依赖这个顺序。
 */
val BALANCE_KINDS: List<BalanceKind> = listOf(
    BalanceKind.NONE,
    BalanceKind.NEWAPI,
    BalanceKind.DEEPSEEK,
    BalanceKind.OPENROUTER,
    BalanceKind.SILICONFLOW,
    BalanceKind.MOONSHOT,
    BalanceKind.CUSTOM_JSON,
)

/** 鉴权风格下拉的顺序，对应 `R.array.auth_styles`。 */
val AUTH_STYLES: List<AuthStyle> = listOf(AuthStyle.AUTO, AuthStyle.BEARER, AuthStyle.X_API_KEY)

fun Provider.toDraft(groupIndex: Int): ProviderDraft = ProviderDraft(
    id = id,
    name = name,
    note = note.orEmpty(),
    website = websiteUrl.orEmpty(),
    // 原样输入而不是规范化结果：编辑时该看到自己当初填的那一行，预览负责显示它变成了什么
    baseUrl = apiBaseUrl,
    groupIndex = groupIndex,
    colorIndex = color ?: 0,
    pinned = pinned,
    protocols = supportedProtocols,
    balanceKindIndex = BALANCE_KINDS.indexOf(balanceKind).coerceAtLeast(0),
    balanceUserId = balanceUserId.orEmpty(),
    // 客户端预设还没有仓库（内置预设的 seed 在 M5），所以这一格暂时固定 0
    profileIndex = 0,
    probeEnabled = probe.enabled,
    probeReachability = probe.reachability,
    probeKeys = probe.keyValidity,
    probeModels = probe.models,
    probeBalance = probe.balance,
    pathOverrideAnthropic = pathOverrides[Protocol.ANTHROPIC].orEmpty(),
    authStyleIndex = AUTH_STYLES.indexOf(authStyle).coerceAtLeast(0),
    timeoutSeconds = timeoutSeconds?.toString().orEmpty(),
    allowInsecure = allowInsecure,
)

/**
 * 草稿 → 领域对象。
 *
 * @param existing 编辑时那一行的当前值。**必须带上**：探测结果、余额快照、`sortOrder`
 *   这些字段不在草稿里，不带就等于每次保存都把它们清空——而"改了个备注，健康状态变未探测"
 *   这种 bug 没人会怀疑到编辑页。
 * @param normalized 已经规范化成功的地址。规范化在调用方做，因为失败时要给出提示而不是存。
 */
fun ProviderDraft.toProvider(
    existing: Provider?,
    normalized: NormalizeResult.Ok,
    groups: List<Group>,
): Provider {
    val endpoints = normalized.endpoints
    val base = existing ?: Provider(name = name, apiBaseUrl = baseUrl, apiRoot = endpoints.apiRoot)
    return base.copy(
        id = existing?.id ?: 0L,
        name = name,
        note = note.ifBlank { null },
        websiteUrl = website.ifBlank { null },
        apiBaseUrl = baseUrl,
        apiRoot = endpoints.apiRoot,
        apiVersion = endpoints.ver,
        supportedProtocols = protocols,
        pathOverrides = if (pathOverrideAnthropic.isBlank()) {
            emptyMap()
        } else {
            mapOf(Protocol.ANTHROPIC to pathOverrideAnthropic)
        },
        authStyle = AUTH_STYLES.getOrElse(authStyleIndex) { AuthStyle.AUTO },
        allowInsecure = allowInsecure,
        // 下标 0 是「未分组」。下标越界时保留原来那一组而不是清空：越界只可能是
        // "分组列表还没加载完"，而把它当成"用户要取消分组"会让一次保存悄悄改掉分组
        groupId = if (groupIndex == 0) {
            null
        } else {
            groups.getOrNull(groupIndex - 1)?.id ?: existing?.groupId
        },
        color = colorIndex,
        pinned = pinned,
        balanceKind = BALANCE_KINDS.getOrElse(balanceKindIndex) { BalanceKind.NONE },
        // 余额地址默认用 origin：中转站的余额接口挂在站点根上，而不是 API 版本段下面
        balanceBaseUrl = existing?.balanceBaseUrl ?: endpoints.origin,
        balanceUserId = balanceUserId.ifBlank { null },
        // NewAPI 系的换算比先给实测默认值；`/api/status` 校准过之后不要覆盖它（§9.2）
        quotaPerUnit = existing?.quotaPerUnit
            ?: BalanceKind.NEWAPI_DEFAULT_QUOTA_PER_UNIT.takeIf {
                BALANCE_KINDS.getOrElse(balanceKindIndex) { BalanceKind.NONE } == BalanceKind.NEWAPI
            },
        timeoutSeconds = timeoutSeconds.trim().toIntOrNull(),
        probe = ProviderProbeSettings(
            enabled = probeEnabled,
            reachability = probeReachability,
            keyValidity = probeKeys,
            balance = probeBalance,
            models = probeModels,
        ),
    )
}

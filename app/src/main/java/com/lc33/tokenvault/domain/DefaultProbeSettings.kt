package com.lc33.tokenvault.domain

/**
 * 新建供应商时的探测默认值（§8.3、红线 36）。
 *
 * 这不是总开关——权威在每家供应商自己的 `providers` 行上，这里只是「新建时从设置拷一份
 * 进 `ProviderDraft`」的初值。改这里不会动已有的供应商，每家站的规则不一样。
 *
 * `models`（L3）默认关：它要花钱、只能手动触发（红线 36）。
 */
data class DefaultProbeSettings(
    val reachability: Boolean = true,
    val keys: Boolean = true,
    val balance: Boolean = true,
    val models: Boolean = false,
)

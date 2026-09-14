package com.lc33.tokenvault.domain

/**
 * 新建**密钥**时的探测默认值（§8.3、红线 36）。
 *
 * 这不是总开关——v3 起权威在每把 Key 自己的 `key_settings` 上，这里只是「新建 Key 时
 * 从设置拷一份进 `KeyDraft`」的初值，读取点在 `KeyEditorViewModel.defaultDraft`。
 * 改这里不会动已有的 Key，每家站的规则不一样。
 *
 * `models` 表示模型列表自动检测，默认关；`modelReachability` 是快捷模型探测，也默认关（要花钱，只能手动触发）。
 */
data class DefaultProbeSettings(
    val reachability: Boolean = true,
    val keys: Boolean = true,
    val balance: Boolean = true,
    val models: Boolean = false,
    val modelReachability: Boolean = false,
)

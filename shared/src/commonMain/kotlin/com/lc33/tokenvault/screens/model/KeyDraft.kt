package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.domain.Protocol

/** Key 设置页的草稿。余额令牌与密钥明文不进这里，保存时单独用 CharArray 交出。 */
data class KeyDraft(
    val id: Long = 0,
    val providerId: Long = 0,
    val label: String = "",
    val note: String = "",
    val sortOrder: Int = 0,
    val baseUrl: String = "",
    val protocols: Set<Protocol> = setOf(Protocol.CHAT),
    val authStyleIndex: Int = 0,
    val profileIndex: Int = 0,
    val pathOverrideAnthropic: String = "",
    val timeoutSeconds: String = "",
    val allowInsecure: Boolean = false,
    /** 下拉下标，指向 [com.lc33.tokenvault.ui.shell.KEY_BALANCE_KINDS]（**不含** NONE）。 */
    val balanceKindIndex: Int = 0,
    /** 「启用余额查询」总开关。关掉即落库为 `BalanceKind.NONE`，下方选择照常保存。 */
    val balanceEnabled: Boolean = false,
    val balanceUserId: String = "",
    val balanceMethod: String = "GET",
    val balancePath: String = "",
    val balanceValuePath: String = "",
    val balanceUsedPath: String = "",
    val balanceCurrency: String = "",
    val probeEnabled: Boolean = true,
    val probeReachability: Boolean = true,
    val probeKeys: Boolean = true,
    val probeBalance: Boolean = true,
    val probeModels: Boolean = false,
    val probeModelReachability: Boolean = false,
    val probeQuickModel: Boolean = false,
)

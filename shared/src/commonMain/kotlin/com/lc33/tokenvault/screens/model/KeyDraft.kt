package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.domain.Protocol

/** Key 设置页的草稿。余额令牌与密钥明文不进这里，保存时单独用 CharArray 交出。 */
data class KeyDraft(
    val id: Long = 0,
    val providerId: Long = 0,
    val label: String = "",
    val note: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val baseUrl: String = "",
    val protocols: Set<Protocol> = setOf(Protocol.CHAT),
    val authStyleIndex: Int = 0,
    val profileIndex: Int = 0,
    val pathOverrideAnthropic: String = "",
    val timeoutSeconds: String = "",
    val allowInsecure: Boolean = false,
    val balanceKindIndex: Int = 0,
    val balanceUserId: String = "",
    val probeEnabled: Boolean = true,
    val probeReachability: Boolean = true,
    val probeKeys: Boolean = true,
    val probeBalance: Boolean = true,
    val probeModels: Boolean = false,
    val probeModelReachability: Boolean = false,
)

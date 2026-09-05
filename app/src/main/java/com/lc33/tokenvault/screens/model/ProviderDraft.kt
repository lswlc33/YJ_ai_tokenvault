package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.endpoint.Protocol

/**
 * 供应商编辑页的草稿。
 *
 * 每个字段都对应一个持久化列，这是红线 16 的清单：这个类里有什么，编辑页就必须有
 * 对应的入口。反过来说，编辑页看起来长是应该的——短了就说明有列没有入口。
 *
 * 密钥 / 模型 / 平台账号是子列表，M3 接真数据时会作为独立的可增删列表挂进来；
 * M0.8 阶段编辑页先只管供应商本身的字段。
 */
data class ProviderDraft(
    val id: Long? = null,
    val name: String = "",
    val note: String = "",
    val website: String = "",
    val baseUrl: String = "",

    // 外观
    val groupIndex: Int = 0,
    val colorIndex: Int = 0,
    val pinned: Boolean = false,

    val protocols: Set<Protocol> = setOf(Protocol.CHAT),

    /** 下标对应 `R.array.balance_kinds`：0 = 不查，1 = newapi，其余复用默认 Key。 */
    val balanceKindIndex: Int = 0,
    val balanceToken: String = "",
    val balanceUserId: String = "",

    val profileIndex: Int = 0,

    // 高级
    val pathOverrideAnthropic: String = "",
    val authStyleIndex: Int = 0,
    /** 空串表示用全局值。对应 `providers.timeoutSeconds`。 */
    val timeoutSeconds: String = "",
    /** 地址是 `http://` 时必须显式打开才允许发请求（§7.5）。 */
    val allowInsecure: Boolean = false,
)

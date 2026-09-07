package com.lc33.tokenvault.screens.model

import com.lc33.tokenvault.domain.Protocol

/**
 * 供应商编辑页的草稿。
 *
 * 每个字段都对应一个持久化列，这是红线 16 的清单：这个类里有什么，编辑页就必须有
 * 对应的入口。反过来说，编辑页看起来长是应该的——短了就说明有列没有入口。
 *
 * 密钥 / 模型 / 平台账号**不在这里**：它们是供应商详情页的子列表（密钥可增删改，
 * 模型与账号当前纯只读展示），不在编辑页草稿里——这个草稿只管供应商本身的字段。
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

    /**
     * 用户 ID。**访问令牌不在这里**——它是明文秘密，只允许活在能擦掉的 `CharArray` 里
     * （红线 1），而这个类是 Compose 长期持有、还会进快照系统的状态。
     * 编辑页把它作为 `onSave` 的第二个参数单独交出去。
     */
    val balanceUserId: String = "",

    val profileIndex: Int = 0,

    /**
     * 探测开关，**每家单独存**（红线 36）。
     *
     * 新建时从设置页「新建默认值」拷一份进来（`ProviderEditorViewModel` 在新建分支读
     * `SettingsRepository.observeDefaultProbeSettings()`），不是硬编码；之后这家供应商
     * 独立，改设置不会动它。每家站的规则都不一样：有的按 ToS 不允许自动化探测，
     * 有的三个请求就限流，有的每次调用都真扣钱，全局一个开关表达不了"这一家别碰"。
     */
    val probeEnabled: Boolean = true,
    val probeReachability: Boolean = true,
    val probeKeys: Boolean = true,
    /** L3。**要钱，只能手动触发**；关掉之后连手动按钮都不出现。 */
    val probeModels: Boolean = false,
    val probeBalance: Boolean = true,

    // 高级
    val pathOverrideAnthropic: String = "",
    val authStyleIndex: Int = 0,
    /** 空串表示用全局值。对应 `providers.timeoutSeconds`。 */
    val timeoutSeconds: String = "",
    /** 地址是 `http://` 时必须显式打开才允许发请求（§7.5）。 */
    val allowInsecure: Boolean = false,
)

package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ClientProfile

/**
 * 客户端伪装自动嗅探（§8.2）的**计划生成**。纯函数，可 JVM 单测。
 *
 * 探测出 `CLIENT_BLOCKED` 后（手动探测且开了开关），按固定顺序重试：
 * 1. **先换鉴权头**（Bearer ↔ x-api-key）——Agent Router 的闸只挂在 Bearer 上，换 x-api-key
 *    一次请求就能过，比换预设便宜（§5.2 末尾、§8.2 第 4 点）。
 * 2. **再按序试内置预设，最多 4 个**——匹配本协议的排前、不匹配的也要试（§8.2：
 *    「适用协议」是排序提示不是硬过滤，中转站的闸只看请求头不看路由）。
 *
 * 只产计划不产结果：每个 [SniffAttempt] 是"用哪种鉴权风格 + 换哪个预设"的纯描述，
 * 真正发请求、写回 `clientProfileId` / `authStyle` 是引擎的事。
 */
data class SniffAttempt(
    /** 这次要用哪种鉴权风格。 */
    val authStyle: AuthStyle,

    /** 换哪个预设；null = 保持当前预设（只换鉴权头那一步）。 */
    val profile: ClientProfile? = null,
)

object SniffPlanBuilder {

    /** 一轮嗅探最多试几个预设（§8.2）。 */
    const val MAX_PROFILE_ATTEMPTS = 4

    /**
     * 生成一轮嗅探的重试顺序。
     *
     * @param authStyle 供应商当前的鉴权风格（`AUTO` 会先落到协议默认）。
     * @param currentProfileId 当前那家用的预设（嗅探时跳过它——它已经失败过了）。
     * @param profiles 全量预设（含内置与自定义）。只取内置、非空位的做候选。
     */
    fun build(
        protocol: Protocol,
        authStyle: AuthStyle,
        currentProfileId: Long?,
        profiles: List<ClientProfile>,
    ): List<SniffAttempt> {
        val effective = if (authStyle == AuthStyle.AUTO) protocol.defaultAuthStyle else authStyle
        val attempts = mutableListOf<SniffAttempt>()

        // 1. 换鉴权头，预设不动。
        attempts += SniffAttempt(authStyle = swap(effective), profile = null)

        // 2. 按序试预设。鉴权风格用 effective——预设解决的是 UA / 特征头，鉴权头是另一条独立
        //    的策略，不该被预设带偏。
        for (profile in candidates(protocol, profiles, currentProfileId)) {
            attempts += SniffAttempt(authStyle = effective, profile = profile)
        }
        return attempts
    }

    /** 换一种鉴权头。`AUTO` 不该出现在这里（调用方已解析），兜底返回原值。 */
    fun swap(style: AuthStyle): AuthStyle = when (style) {
        AuthStyle.BEARER -> AuthStyle.X_API_KEY
        AuthStyle.X_API_KEY -> AuthStyle.BEARER
        AuthStyle.AUTO -> AuthStyle.AUTO
    }

    /**
     * 候选预设：内置、非 `default`（那是"不伪装"基线，已经失败过）、非当前预设、UA 非空，
     * 匹配本协议的排前，最多 [MAX_PROFILE_ATTEMPTS] 个。
     */
    fun candidates(
        protocol: Protocol,
        profiles: List<ClientProfile>,
        currentProfileId: Long?,
    ): List<ClientProfile> = profiles
        .filter { it.builtinKey != null && it.builtinKey != "default" && it.userAgent.isNotBlank() }
        .filter { it.id != currentProfileId }
        .sortedWith(compareBy({ protocol !in it.protocols }, { it.sortOrder }))
        .take(MAX_PROFILE_ATTEMPTS)
}

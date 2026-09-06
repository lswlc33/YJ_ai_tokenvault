package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ClientProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 测试：客户端伪装自动嗅探的计划生成（计划.md §8.2）。
 *
 * [SniffPlanBuilder] 是纯函数，这里逐条验证 §8.2 的嗅探顺序语义：
 * 1. 先换鉴权头（Bearer ↔ x-api-key），预设不动。
 * 2. 再按序试内置预设，最多 4 个。
 * 3. 「适用协议」是排序提示不是硬过滤——匹配本协议的排前，不匹配的也要试。
 * 4. 跳过当前预设、`default` 基线、空 UA、自定义预设。
 *
 * 429 熔断（红线 29）在编排器层由 [ProbeOrchestrator] 的 `hostRateLimited` 承担，
 * 已由 `ProbeOrchestratorTest.429 后立即停止该 host 后续请求` 覆盖；引擎 `trySniff`
 * 里的那份是它的镜像，依赖真实网络栈，属集成验证范畴。
 */
class SniffPlanTest {

    private fun profile(
        id: Long,
        builtinKey: String? = "k$id",
        userAgent: String = "UA-$id",
        protocols: Set<Protocol> = emptySet(),
        sortOrder: Int = id.toInt(),
    ) = ClientProfile(
        id = id,
        name = "p$id",
        builtinKey = builtinKey,
        userAgent = userAgent,
        protocols = protocols,
        sortOrder = sortOrder,
    )

    @Test
    fun `第一步换鉴权头且预设不动`() {
        val plan = SniffPlanBuilder.build(
            protocol = Protocol.CHAT,
            authStyle = AuthStyle.BEARER,
            currentProfileId = null,
            profiles = listOf(profile(1), profile(2)),
        )

        // 第一步是纯换鉴权头：authStyle 换成相反的，profile 为 null（保持当前预设）。
        assertEquals(AuthStyle.X_API_KEY, plan.first().authStyle)
        assertNull(plan.first().profile)
    }

    @Test
    fun `AUTO 先落到协议默认再换`() {
        // ANTHROPIC 默认是 x-api-key → swap 后是 Bearer。
        val plan = SniffPlanBuilder.build(
            protocol = Protocol.ANTHROPIC,
            authStyle = AuthStyle.AUTO,
            currentProfileId = null,
            profiles = emptyList(),
        )
        assertEquals(AuthStyle.BEARER, plan.first().authStyle)
    }

    @Test
    fun `匹配本协议的预设排前`() {
        val profiles = listOf(
            profile(1, protocols = emptySet()),            // 通用
            profile(2, protocols = setOf(Protocol.CHAT)),  // 匹配 CHAT
            profile(3, protocols = emptySet()),            // 通用
        )
        val plan = SniffPlanBuilder.build(
            protocol = Protocol.CHAT,
            authStyle = AuthStyle.BEARER,
            currentProfileId = null,
            profiles = profiles,
        )

        // 第一个预设候选应是匹配 CHAT 的那个，其余通用预设排在后面（不是硬过滤掉）。
        val profileIds = plan.drop(1).map { it.profile!!.id }
        assertEquals(listOf(2L, 1L, 3L), profileIds)
    }

    @Test
    fun `最多试四个预设`() {
        val profiles = (1..6).map { profile(it.toLong()) }
        val plan = SniffPlanBuilder.build(
            protocol = Protocol.CHAT,
            authStyle = AuthStyle.BEARER,
            currentProfileId = null,
            profiles = profiles,
        )

        // 1 步换鉴权头 + 最多 4 个预设 = 5 次尝试。
        assertEquals(5, plan.size)
        assertEquals(4, plan.count { it.profile != null })
    }

    @Test
    fun `跳过当前预设 default 空 UA 与自定义`() {
        val profiles = listOf(
            profile(1, builtinKey = "default", userAgent = ""), // default 基线 + 空 UA
            profile(2, builtinKey = null, userAgent = "UA-custom"), // 自定义
            profile(3, builtinKey = "claude", userAgent = "UA-claude"), // 当前预设 → 跳过
            profile(4, builtinKey = "codex", userAgent = "UA-codex"),
        )
        val candidates = SniffPlanBuilder.candidates(
            protocol = Protocol.CHAT,
            profiles = profiles,
            currentProfileId = 3,
        )
        assertEquals(listOf(4L), candidates.map { it.id })
    }

    @Test
    fun `swap 是双向的`() {
        assertEquals(AuthStyle.X_API_KEY, SniffPlanBuilder.swap(AuthStyle.BEARER))
        assertEquals(AuthStyle.BEARER, SniffPlanBuilder.swap(AuthStyle.X_API_KEY))
    }
}

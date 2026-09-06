package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.ModelSource
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.AiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 12：模型列表三路合并（计划.md §14.3，§8.3，红线 13、30）。
 *
 * 五个断言点：
 * - 新增：列表里有、库里没有 → 插入（discovered + 本协议）。
 * - 保留：两边都有 → touch lastSeenAt。
 * - 消失置停用：discovered + 本协议，但列表里没有 → 停用。
 * - manual 行不动。
 * - **只查一个协议时不动其它协议的发现项**（红线 30）。
 */
class ModelMergerTest {

    private fun model(
        id: Long,
        modelId: String,
        protocol: Protocol = Protocol.CHAT,
        source: ModelSource = ModelSource.MANUAL,
        discoveredVia: Protocol? = null,
    ) = AiModel(
        id = id,
        providerId = 1,
        modelId = modelId,
        protocol = protocol,
        source = source,
        discoveredVia = discoveredVia,
    )

    private fun fetched(modelId: String, protocol: Protocol = Protocol.CHAT) =
        NewDiscoveredModel(modelId, protocol)

    @Test
    fun `列表有库里没有则插入`() {
        val plan = ModelMerger.merge(
            existing = emptyList(),
            fetched = listOf(fetched("gpt-5.6-sol"), fetched("gpt-4o")),
            thisProtocol = Protocol.CHAT,
        )
        assertEquals(setOf("gpt-5.6-sol", "gpt-4o"), plan.toInsert.map { it.modelId }.toSet())
        assertTrue(plan.toTouch.isEmpty())
        assertTrue(plan.toDisable.isEmpty())
    }

    @Test
    fun `两边都有则 touch 而不插入`() {
        val existing = model(1, "gpt-5.6-sol", source = ModelSource.DISCOVERED, discoveredVia = Protocol.CHAT)
        val plan = ModelMerger.merge(
            existing = listOf(existing),
            fetched = listOf(fetched("gpt-5.6-sol")),
            thisProtocol = Protocol.CHAT,
        )
        assertEquals(listOf(1L), plan.toTouch)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toDisable.isEmpty())
    }

    @Test
    fun `消失的 discovered 且本协议则停用`() {
        val a = model(1, "gpt-5.6-sol", source = ModelSource.DISCOVERED, discoveredVia = Protocol.CHAT)
        val b = model(2, "gpt-4o", source = ModelSource.DISCOVERED, discoveredVia = Protocol.CHAT)
        val plan = ModelMerger.merge(
            existing = listOf(a, b),
            fetched = listOf(fetched("gpt-5.6-sol")),
            thisProtocol = Protocol.CHAT,
        )
        assertEquals(listOf(2L), plan.toDisable)
    }

    @Test
    fun `manual 行永不被自动同步停用`() {
        val manual = model(1, "my-custom-model", source = ModelSource.MANUAL)
        val plan = ModelMerger.merge(
            existing = listOf(manual),
            fetched = emptyList(),
            thisProtocol = Protocol.CHAT,
        )
        assertTrue(plan.toDisable.isEmpty())
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test
    fun `只查 CHAT 时不动 ANTHROPIC 的发现项`() {
        val chatModel = model(1, "gpt-5.6-sol", source = ModelSource.DISCOVERED, discoveredVia = Protocol.CHAT)
        val anthropicModel = model(2, "claude-opus-5", protocol = Protocol.ANTHROPIC, source = ModelSource.DISCOVERED, discoveredVia = Protocol.ANTHROPIC)
        val plan = ModelMerger.merge(
            existing = listOf(chatModel, anthropicModel),
            fetched = emptyList(), // 本轮 CHAT 列表是空的（比如拉失败）
            thisProtocol = Protocol.CHAT,
        )
        // 只停用 CHAT 的发现项，ANTHROPIC 的不动
        assertEquals(listOf(1L), plan.toDisable)
    }

    @Test
    fun `列表里其它协议的模型不并入本协议的合并`() {
        val plan = ModelMerger.merge(
            existing = emptyList(),
            fetched = listOf(fetched("claude-opus-5", Protocol.ANTHROPIC)),
            thisProtocol = Protocol.CHAT,
        )
        assertTrue(plan.toInsert.isEmpty())
    }
}

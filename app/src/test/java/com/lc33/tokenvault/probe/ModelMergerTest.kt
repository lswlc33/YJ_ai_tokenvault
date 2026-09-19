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
 * - 消失即删除：discovered + 本协议，但列表里没有 → 删除。
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
        assertTrue(plan.toDelete.isEmpty())
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
        assertTrue(plan.toDelete.isEmpty())
    }

    @Test
    fun `消失的 discovered 且本协议则删除`() {
        val a = model(1, "gpt-5.6-sol", source = ModelSource.DISCOVERED, discoveredVia = Protocol.CHAT)
        val b = model(2, "gpt-4o", source = ModelSource.DISCOVERED, discoveredVia = Protocol.CHAT)
        val plan = ModelMerger.merge(
            existing = listOf(a, b),
            fetched = listOf(fetched("gpt-5.6-sol")),
            thisProtocol = Protocol.CHAT,
        )
        assertEquals(listOf(2L), plan.toDelete)
    }

    @Test
    fun `manual 行永不被自动同步删除`() {
        val manual = model(1, "my-custom-model", source = ModelSource.MANUAL)
        val plan = ModelMerger.merge(
            existing = listOf(manual),
            fetched = emptyList(),
            thisProtocol = Protocol.CHAT,
        )
        assertTrue(plan.toDelete.isEmpty())
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
        // 只删 CHAT 的发现项，ANTHROPIC 的不动
        assertEquals(listOf(1L), plan.toDelete)
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

    @Test
    fun `same model id on different protocol is inserted`() {
        val plan = ModelMerger.merge(
            existing = listOf(model(1, "shared-model", protocol = Protocol.CHAT)),
            fetched = listOf(fetched("shared-model", Protocol.ANTHROPIC)),
            thisProtocol = Protocol.ANTHROPIC,
        )
        assertEquals(listOf(NewDiscoveredModel("shared-model", Protocol.ANTHROPIC)), plan.toInsert)
    }

    // -------------------------------------------------- 一个模型只留一个协议
    //
    // 上面那条是合并层的既有语义（"给了两个协议就落两行"），这一组是**在它之前**收一次口：
    // 解析层把 `["openai"]` 摊成 chat + responses 是事实，但照原样往下传就是同一个模型
    // 两行、界面上一份重复列表，而"消失即删"按协议各管一套，重复每刷新一次长回来一次。

    @Test
    fun `摊成多个协议的模型只留首选协议那一行`() {
        val folded = ModelMerger.oneProtocolPerModel(
            discovered = listOf(
                fetched("gpt-5.6-sol", Protocol.CHAT),
                fetched("gpt-5.6-sol", Protocol.RESPONSES),
                fetched("gpt-4o", Protocol.CHAT),
            ),
            allowed = setOf(Protocol.CHAT, Protocol.RESPONSES),
            preferred = Protocol.CHAT,
        )

        assertEquals(
            listOf(NewDiscoveredModel("gpt-5.6-sol", Protocol.CHAT), NewDiscoveredModel("gpt-4o", Protocol.CHAT)),
            folded,
        )
    }

    @Test
    fun `首选协议不在候选里就用候选自己的第一个`() {
        // 只标了 anthropic 的模型不该被强行记成 chat——那样它会发到 /chat/completions
        // 而上游只有 /messages，表现是 404，且很难反推回这里。
        val folded = ModelMerger.oneProtocolPerModel(
            discovered = listOf(
                fetched("claude-opus-5", Protocol.CHAT),
                fetched("claude-opus-5", Protocol.ANTHROPIC),
            ),
            allowed = setOf(Protocol.CHAT, Protocol.ANTHROPIC),
            preferred = Protocol.ANTHROPIC,
        )

        assertEquals(listOf(NewDiscoveredModel("claude-opus-5", Protocol.ANTHROPIC)), folded)
    }

    @Test
    fun `Key 没声明的协议不落库`() {
        val folded = ModelMerger.oneProtocolPerModel(
            discovered = listOf(
                fetched("kimi-k3", Protocol.CHAT),
                fetched("kimi-k3", Protocol.RESPONSES),
            ),
            allowed = setOf(Protocol.CHAT),
            preferred = Protocol.CHAT,
        )

        assertEquals(listOf(NewDiscoveredModel("kimi-k3", Protocol.CHAT)), folded)
    }

    @Test
    fun `一个协议都没声明时什么都不留`() {
        // 兜住 `key == null` / 设置读空：宁可什么都不写，也别拿一个猜出来的协议落库。
        val folded = ModelMerger.oneProtocolPerModel(
            discovered = listOf(fetched("gpt-4o", Protocol.CHAT)),
            allowed = emptySet(),
            preferred = null,
        )

        assertTrue(folded.isEmpty())
    }
}

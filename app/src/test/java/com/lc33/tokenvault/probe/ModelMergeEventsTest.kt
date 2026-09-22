package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.ModelChangeKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 一轮三路合并落地成上下架流水（`ModelMergeEvents`）。
 *
 * 四条判据各一条，因为每一条都对应一种"报错了比不报更糟"：
 * - 首轮整批抓取不记新增（把"第一次看这家的列表"念成"上了 445 个新模型"是最难被发现的假话）。
 * - 本轮列表里还在的不算下架（跨协议重建那批删除每刷一次就来一遍）。
 * - 真消失了的算下架，并按那一行自己的 Key 与协议记。
 * - 手动行不进流水（红线 13 之外再挡一道）。
 */
class ModelMergeEventsTest {

    private fun row(
        modelId: String,
        keyId: Long? = 7L,
        protocol: String = "chat",
        source: String = "discovered",
    ) = DeletedModelRow(modelId = modelId, keyId = keyId, protocol = protocol, source = source)

    private fun events(
        inserted: List<String>,
        deleted: List<DeletedModelRow>,
        listed: Set<String>,
        firstRound: Boolean,
    ) = ModelMergeEvents.of(
        providerId = 1L,
        keyId = 7L,
        protocol = "chat",
        insertedModelIds = inserted,
        deletedRows = deleted,
        listedModelIds = listed,
        firstRoundForKey = firstRound,
        at = 1000L,
    )

    @Test
    fun `一张 Key 的第一轮整批抓取不记新增`() {
        val out = events(inserted = listOf("a", "b", "c"), deleted = emptyList(), listed = setOf("a", "b", "c"), firstRound = true)
        assertEquals(emptyList<String>(), out.map { it.modelId })
    }

    @Test
    fun `第二轮才认新增，并按本轮的 Key 与协议记`() {
        val out = events(inserted = listOf("new-one"), deleted = emptyList(), listed = setOf("new-one"), firstRound = false)
        assertEquals(1, out.size)
        assertEquals("new-one", out.single().modelId)
        assertEquals(ModelChangeKind.ADDED, out.single().kind)
        assertEquals(7L, out.single().keyId)
        assertEquals("chat", out.single().protocol)
        assertEquals(1000L, out.single().at)
    }

    @Test
    fun `本轮列表里还在的那条删除不算下架`() {
        // `ModelMerger` 第四条规则：模型还在，只是行躺错了协议，删旧行由本轮重建。
        val out = events(
            inserted = listOf("gpt-4o"),
            deleted = listOf(row("gpt-4o", protocol = "responses")),
            listed = setOf("gpt-4o"),
            firstRound = false,
        )
        assertEquals(listOf("gpt-4o"), out.map { it.modelId })
        assertEquals(listOf(ModelChangeKind.ADDED), out.map { it.kind })
    }

    @Test
    fun `本轮列表里没有的发现行算下架`() {
        val out = events(
            inserted = emptyList(),
            deleted = listOf(row("gone-model", keyId = 9L, protocol = "anthropic")),
            listed = emptySet(),
            firstRound = false,
        )
        val single = out.single()
        assertEquals(ModelChangeKind.REMOVED, single.kind)
        assertEquals("gone-model", single.modelId)
        assertEquals("下架要按那一行自己的 Key 记，本轮的 Key 可能根本不是它的主人", 9L, single.keyId!!)
        assertEquals("anthropic", single.protocol)
    }

    @Test
    fun `手动录入的行不进流水`() {
        val out = events(
            inserted = emptyList(),
            deleted = listOf(row("my-alias", source = "manual")),
            listed = emptySet(),
            firstRound = false,
        )
        assertEquals(emptyList<String>(), out.map { it.modelId })
    }
}

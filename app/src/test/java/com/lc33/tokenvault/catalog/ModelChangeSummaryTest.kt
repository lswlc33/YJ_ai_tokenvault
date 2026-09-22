package com.lc33.tokenvault.catalog

import com.lc33.tokenvault.domain.ModelChangeKind
import com.lc33.tokenvault.domain.model.ModelChange
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下架流水折成"每家站点新增了什么、下架了什么"（`ModelChangeSummary`）。
 *
 * 时间基准取一个**正好落在 UTC 零点**的毫秒数（2000-01-01T00:00Z）：窗口是按本地日历日算的，
 * 基准不在零点上就会让"整七天前"那条样本落在边界外还是边界内取决于小时数，测试会变成
 * 看时区脸色的随机红。
 */
class ModelChangeSummaryTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 946_684_800_000L
    private val zone = TimeZone.UTC

    private fun change(
        modelId: String,
        kind: ModelChangeKind,
        providerId: Long = 1L,
        at: Long = now,
        id: Long = modelId.hashCode().toLong(),
    ) = ModelChange(
        id = id,
        providerId = providerId,
        keyId = 7L,
        modelId = modelId,
        protocol = "chat",
        kind = kind,
        at = at,
    )

    private fun summarize(
        changes: List<ModelChange>,
        live: Map<Long, Set<String>>,
        rangeDays: Int = 7,
    ) = ModelChangeSummary.summarize(
        changes = changes,
        liveModelIdsByProvider = live,
        rangeDays = rangeDays,
        now = now,
        zone = zone,
    )

    @Test
    fun `空输入给空结果`() {
        assertEquals(emptyList<ProviderModelChanges>(), summarize(emptyList(), emptyMap()))
    }

    @Test
    fun `还在现状里的新增照常念`() {
        val out = summarize(
            changes = listOf(change("fresh", ModelChangeKind.ADDED)),
            live = mapOf(1L to setOf("fresh")),
        )
        val provider = out.single()
        assertEquals(listOf("fresh"), provider.added.map { it.modelId })
        assertEquals(emptyList<String>(), provider.removed.map { it.modelId })
    }

    @Test
    fun `同一个模型按最后一次事件定性，只出现在一段里`() {
        val out = summarize(
            changes = listOf(
                change("flappy", ModelChangeKind.ADDED, at = now - 2 * day),
                change("flappy", ModelChangeKind.REMOVED, at = now - day),
            ),
            live = mapOf(1L to emptySet()),
        )
        val provider = out.single()
        assertEquals(emptyList<String>(), provider.added.map { it.modelId })
        assertEquals(listOf("flappy"), provider.removed.map { it.modelId })
    }

    @Test
    fun `现状里已经没有的新增不念`() {
        // 用户自己删了那行、或撤销恢复把它带回旧状态：站点并没有"又不给了"，
        // 而把它念成新增之后又什么都不说，页面就少了一行还说不清为什么。
        val out = summarize(
            changes = listOf(change("ghost", ModelChangeKind.ADDED)),
            live = mapOf(1L to emptySet()),
        )
        assertEquals(emptyList<ProviderModelChanges>(), out)
    }

    @Test
    fun `现状里还在的下架不念`() {
        val out = summarize(
            changes = listOf(change("back-again", ModelChangeKind.REMOVED)),
            live = mapOf(1L to setOf("back-again")),
        )
        assertEquals(emptyList<ProviderModelChanges>(), out)
    }

    @Test
    fun `窗口之外的变化不念`() {
        val olderThanSevenDays = listOf(change("old", ModelChangeKind.ADDED, at = now - 7 * day))

        assertEquals(emptyList<ProviderModelChanges>(), summarize(olderThanSevenDays, mapOf(1L to setOf("old"))))
        assertEquals(1, summarize(olderThanSevenDays, mapOf(1L to setOf("old")), rangeDays = 30).size)
    }

    @Test
    fun `段内按时间倒序，最近发生的排最前`() {
        val out = summarize(
            changes = listOf(
                change("first", ModelChangeKind.ADDED, at = now - 3 * day),
                change("last", ModelChangeKind.ADDED, at = now),
                change("middle", ModelChangeKind.ADDED, at = now - day),
            ),
            live = mapOf(1L to setOf("first", "last", "middle")),
        )
        assertEquals(listOf("last", "middle", "first"), out.single().added.map { it.modelId })
    }

    @Test
    fun `动得多的站点排前面`() {
        val out = summarize(
            changes = listOf(
                change("a", ModelChangeKind.ADDED, providerId = 1L),
                change("b", ModelChangeKind.ADDED, providerId = 2L),
                change("c", ModelChangeKind.ADDED, providerId = 2L),
                change("d", ModelChangeKind.REMOVED, providerId = 2L, at = now - day),
            ),
            live = mapOf(
                1L to setOf("a"),
                2L to setOf("b", "c"),
            ),
        )
        assertEquals(listOf(2L, 1L), out.map { it.providerId })
        assertEquals(3, out.first().total)
    }
}

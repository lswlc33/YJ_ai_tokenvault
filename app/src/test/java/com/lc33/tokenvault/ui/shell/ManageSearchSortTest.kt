package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.screens.model.ProviderSort
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiMoney
import com.lc33.tokenvault.screens.model.UiProviderRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管理页搜索与排序（§13.4「搜名称、备注、host、分组名」+「排序 chip」）。
 *
 * 单独测的理由：这两处都是纯函数、在内存里对整份列表做过滤/重排，
 * 错一处不会报错，只会让用户"搜不到明明存在的供应商"或者列表在每次回流时乱跳。
 */
class ManageSearchSortTest {

    private fun row(
        id: Long,
        name: String,
        note: String? = null,
        host: String = "api$id.example.test",
        groupId: Long? = null,
        balance: UiMoney? = null,
        sortOrder: Int = 0,
        lastProbeAt: Long? = null,
    ) = UiProviderRow(
        id = id,
        name = name,
        note = note,
        host = host,
        protocols = emptyList(),
        colorIndex = 0,
        pinned = false,
        groupId = groupId,
        keyCount = 1,
        okKeyCount = 1,
        modelCount = 0,
        accountCount = 0,
        balance = balance,
        health = UiHealth.Unknown,
        sortOrder = sortOrder,
        lastProbeAt = lastProbeAt,
    )

    private val groups = listOf(
        Group(id = 1, name = "工作"),
        Group(id = 2, name = "个人"),
    )

    // ---------------------------------------------------------------- 搜索

    @Test
    fun `空查询恒匹配`() {
        assertTrue(matchesQuery(row(1, "OpenAI"), null, ""))
        assertTrue(matchesQuery(row(1, "OpenAI"), null, "   "))
    }

    @Test
    fun `搜名称命中，大小写不敏感`() {
        assertTrue(matchesQuery(row(1, "OpenAI Router"), null, "openai"))
        assertTrue(matchesQuery(row(1, "OpenAI Router"), null, "ROUTER"))
    }

    @Test
    fun `搜备注与 host 也命中`() {
        assertTrue(matchesQuery(row(1, "A", note = "生产环境主号"), null, "生产"))
        assertTrue(matchesQuery(row(1, "A", host = "r.example.com"), null, "example"))
    }

    @Test
    fun `搜分组名命中`() {
        assertTrue(matchesQuery(row(1, "A", groupId = 1), groupNameOf(groups, 1), "工作"))
    }

    @Test
    fun `搜不到返回 false`() {
        assertFalse(matchesQuery(row(1, "OpenAI"), null, "anthropic"))
    }

    @Test
    fun `带空格的查询先去空白再匹配`() {
        // 与 ProbeClassifier 同一套约定：关键词本身去空白，避免 "OpenAI "（带空格）匹配不上
        assertTrue(matchesQuery(row(1, "OpenAI Router"), null, "  openai  "))
    }

    // ---------------------------------------------------------------- 排序

    @Test
    fun `手动排序按 sortOrder 升序，同值按名称兜底`() {
        val sorted = sortProviders(
            listOf(
                row(1, "Beta", sortOrder = 2),
                row(2, "Alpha", sortOrder = 0),
                row(3, "Gamma", sortOrder = 2),
            ),
            ProviderSort.MANUAL,
        )
        assertEquals(listOf("Alpha", "Beta", "Gamma"), sorted.map { it.name })
    }

    @Test
    fun `按名称字典序`() {
        val sorted = sortProviders(
            listOf(row(1, "Zeta"), row(2, "Alpha"), row(3, "Mid")),
            ProviderSort.NAME,
        )
        assertEquals(listOf("Alpha", "Mid", "Zeta"), sorted.map { it.name })
    }

    @Test
    fun `按余额降序，没余额的排最后`() {
        val sorted = sortProviders(
            listOf(
                row(1, "Rich", balance = UiMoney("USD", "100.00")),
                row(2, "Poor", balance = UiMoney("USD", "1.00")),
                row(3, "None"),
                row(4, "Mid", balance = UiMoney("USD", "50.00")),
            ),
            ProviderSort.BALANCE,
        )
        assertEquals(listOf("Rich", "Mid", "Poor", "None"), sorted.map { it.name })
    }

    @Test
    fun `异币种不比金额，按币种字母序分组`() {
        val sorted = sortProviders(
            listOf(
                row(1, "Usd", balance = UiMoney("USD", "10.00")),
                row(2, "Cny", balance = UiMoney("CNY", "999.00")),
                row(3, "UsdRich", balance = UiMoney("USD", "500.00")),
            ),
            ProviderSort.BALANCE,
        )
        // CNY 999 数值再大，也和 USD 没有可比性（§9.3 不做汇率换算），
        // 所以按币种字母序分块：CNY 在前、USD 在后，各自块内按金额降序。
        assertEquals(listOf("Cny", "UsdRich", "Usd"), sorted.map { it.name })
    }

    @Test
    fun `最近探测降序，从没测过的排最后`() {
        val sorted = sortProviders(
            listOf(
                row(1, "Old", lastProbeAt = 100),
                row(2, "New", lastProbeAt = 900),
                row(3, "Never"),
                row(4, "Mid", lastProbeAt = 500),
            ),
            ProviderSort.LAST_PROBE,
        )
        assertEquals(listOf("New", "Mid", "Old", "Never"), sorted.map { it.name })
    }

    @Test
    fun `排序是稳定的，不改动原列表`() {
        val rows = listOf(row(1, "Beta"), row(2, "Alpha"))
        val sorted = sortProviders(rows, ProviderSort.NAME)
        assertEquals(listOf("Beta", "Alpha"), rows.map { it.name })
        assertEquals(listOf("Alpha", "Beta"), sorted.map { it.name })
    }
}

package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.screens.model.AttentionKind
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiMoney
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardAggregationTest {

    private fun provider(id: Long, name: String = "Provider $id") =
        Provider(id = id, name = name)

    private fun summary(
        provider: Provider,
        keyCount: Int = 0,
        okKeyCount: Int = 0,
        modelCount: Int = 0,
        accountCount: Int = 0,
    ) = ProviderSummary(provider, keyCount, okKeyCount, modelCount, accountCount)

    private fun key(
        id: Long,
        providerId: Long,
        health: KeyHealth = KeyHealth.UNKNOWN,
        enabled: Boolean = true,
        balance: BalanceSnapshot? = null,
    ) = ApiKey(
        id = id,
        providerId = providerId,
        label = "key$id",
        note = "",
        secretEnc = ByteArray(0),
        fingerprint = "fp$id",
        enabled = enabled,
        settings = KeySettings(apiBaseUrl = "https://api$providerId.example.test/v1", apiRoot = "https://api$providerId.example.test"),
        health = health,
        balance = balance,
    )

    @Test
    fun `计数是聚合查询那四列的和`() {
        val counts = contentCountsOf(
            listOf(
                summary(provider(1), keyCount = 2, modelCount = 5, accountCount = 1),
                summary(provider(2), keyCount = 1, modelCount = 3, accountCount = 0),
            ),
        )
        assertEquals(2, counts.providers)
        assertEquals(3, counts.keys)
        assertEquals(8, counts.models)
        assertEquals(1, counts.accounts)
    }

    @Test
    fun `一家都没有时全是零而不是空态之外的什么东西`() {
        val counts = contentCountsOf(emptyList())
        assertEquals(0, counts.providers)
        assertEquals(0, counts.keys)
    }

    @Test
    fun `健康分布按四档归并`() {
        val breakdown = healthBreakdownOf(
            listOf(
                key(1, 1, KeyHealth.OK),
                key(2, 1, KeyHealth.UNAUTHORIZED),
                key(3, 1, KeyHealth.FORBIDDEN),
                key(4, 1, KeyHealth.CLIENT_BLOCKED),
                key(5, 1, KeyHealth.INSUFFICIENT),
                key(6, 1, KeyHealth.UNKNOWN),
            ),
        )
        assertEquals(1, breakdown.ok)
        assertEquals(2, breakdown.error)
        assertEquals(2, breakdown.warn)
        assertEquals(1, breakdown.unknown)
        assertEquals(6, breakdown.total)
    }

    @Test
    fun `停用的密钥不进健康分布`() {
        val breakdown = healthBreakdownOf(
            listOf(
                key(1, 1, KeyHealth.OK),
                key(2, 1, KeyHealth.OK, enabled = false),
            ),
        )
        assertEquals(1, breakdown.total)
        assertTrue(breakdown.allOk)
    }

    @Test
    fun `一把密钥都没有时不算全绿`() {
        assertTrue(!healthBreakdownOf(emptyList()).allOk)
    }

    @Test
    fun `余额按币种分组求和，先舍入再相加`() {
        val summary = keyBalanceSummaryOf(
            listOf(
                key(1, 1, balance = BalanceSnapshot(amount = 42.099999999999994, currency = "USD")),
                key(2, 1, balance = BalanceSnapshot(amount = 0.005, currency = "USD")),
                key(3, 1, balance = BalanceSnapshot(amount = 358.0, currency = "CNY")),
            ),
        )
        assertEquals(
            listOf(UiMoney("CNY", "358.00"), UiMoney("USD", "42.11")),
            summary.perCurrency,
        )
    }

    @Test
    fun `查询失败的不参与合计，只计入失败家数`() {
        val summary = keyBalanceSummaryOf(
            listOf(
                key(1, 1, balance = BalanceSnapshot(amount = 1.0, currency = "USD")),
                key(2, 2, balance = BalanceSnapshot(error = "timeout")),
            ),
        )
        assertEquals(listOf(UiMoney("USD", "1.00")), summary.perCurrency)
        assertEquals(1, summary.failedProviderCount)
    }

    @Test
    fun `失败与成功并存时那家不算失败`() {
        val summary = keyBalanceSummaryOf(
            listOf(
                key(1, 1, balance = BalanceSnapshot(amount = 1.0, currency = "USD")),
                key(2, 1, balance = BalanceSnapshot(error = "timeout")),
            ),
        )
        assertEquals(0, summary.failedProviderCount)
    }

    @Test
    fun `更新时间取最新一次成功或失败`() {
        val summary = keyBalanceSummaryOf(
            listOf(
                key(1, 1, balance = BalanceSnapshot(amount = 1.0, currency = "USD", checkedAt = 100)),
                key(2, 1, balance = BalanceSnapshot(error = "timeout", checkedAt = 900)),
            ),
        )
        assertEquals(900L, summary.updatedAt)
    }

    @Test
    fun `没查过余额时那一行不显示`() {
        val summary = keyBalanceSummaryOf(listOf(key(1, 1)))
        assertTrue(summary.perCurrency.isEmpty())
        assertNull(summary.updatedAt)
    }

    private val thresholds = mapOf("USD" to 5.0)

    @Test
    fun `瞬时失败与未探测都不进需要处理`() {
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1), keyCount = 1)),
            healthByProvider = mapOf(1L to listOf(KeyHealth.UNKNOWN)),
            balanceByProvider = emptyMap(),
            thresholds = thresholds,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `密钥被拒是红的，客户端被拦是黄的`() {
        val items = attentionItemsOf(
            summaries = listOf(
                summary(provider(1, name = "A")),
                summary(provider(2, name = "B")),
            ),
            healthByProvider = mapOf(
                1L to listOf(KeyHealth.UNAUTHORIZED),
                2L to listOf(KeyHealth.CLIENT_BLOCKED),
            ),
            balanceByProvider = emptyMap(),
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.KeyRejected, AttentionKind.ClientBlocked), items.map { it.kind })
        assertEquals(UiHealth.Error, items[0].health)
        assertEquals(UiHealth.Warn, items[1].health)
    }

    @Test
    fun `一家里多把坏密钥只出一行，取最严重的那一档`() {
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1))),
            healthByProvider = mapOf(
                1L to listOf(KeyHealth.CONFIG_ERROR, KeyHealth.UNAUTHORIZED, KeyHealth.CLIENT_BLOCKED),
            ),
            balanceByProvider = emptyMap(),
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.KeyRejected), items.map { it.kind })
    }

    @Test
    fun `额度不足与余额低不刷两行`() {
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1))),
            healthByProvider = mapOf(1L to listOf(KeyHealth.INSUFFICIENT)),
            balanceByProvider = mapOf(1L to BalanceSnapshot(amount = 0.45, currency = "USD")),
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.LowBalance), items.map { it.kind })
    }

    @Test
    fun `密钥坏了同时余额也低，两件事各一行`() {
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1))),
            healthByProvider = mapOf(1L to listOf(KeyHealth.UNAUTHORIZED)),
            balanceByProvider = mapOf(1L to BalanceSnapshot(amount = 0.45, currency = "USD")),
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.KeyRejected, AttentionKind.LowBalance), items.map { it.kind })
    }

    @Test
    fun `没配阈值的币种不判低余额`() {
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1))),
            healthByProvider = emptyMap(),
            balanceByProvider = mapOf(1L to BalanceSnapshot(amount = 0.01, currency = "JPY")),
            thresholds = thresholds,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `负余额一律算需要处理`() {
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1))),
            healthByProvider = emptyMap(),
            balanceByProvider = mapOf(1L to BalanceSnapshot(amount = -3.0, currency = "JPY")),
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.LowBalance), items.map { it.kind })
    }

    @Test
    fun `顺序稳定：先按严重程度，再按名字`() {
        val items = attentionItemsOf(
            summaries = listOf(
                summary(provider(1, name = "Zeta")),
                summary(provider(2, name = "Alpha")),
                summary(provider(3, name = "Mid")),
            ),
            healthByProvider = mapOf(
                1L to listOf(KeyHealth.CONFIG_ERROR),
                2L to listOf(KeyHealth.CONFIG_ERROR),
                3L to listOf(KeyHealth.UNAUTHORIZED),
            ),
            balanceByProvider = emptyMap(),
            thresholds = thresholds,
        )
        assertEquals(listOf("Mid", "Alpha", "Zeta"), items.map { it.providerName })
    }
}

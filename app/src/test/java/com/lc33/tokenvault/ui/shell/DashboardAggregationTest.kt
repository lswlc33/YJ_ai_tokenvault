package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.model.ProviderSummary
import com.lc33.tokenvault.screens.model.AttentionKind
import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiMoney
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表盘那六块卡上的数字（§13.4）。
 *
 * 值得单独测的理由：**这是用户看到的第一屏**，而它上面的每个数都是聚合出来的——
 * 聚合错了不会报错，只会让首屏和管理页各说一个数，然后用户就再也不信这个应用里的数字了。
 */
class DashboardAggregationTest {

    private fun provider(
        id: Long,
        name: String = "Provider $id",
        balance: BalanceSnapshot? = null,
    ) = Provider(
        id = id,
        name = name,
        apiBaseUrl = "https://api$id.example.test/v1",
        apiRoot = "https://api$id.example.test",
        balance = balance,
    )

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
    ) = ApiKey(
        id = id,
        providerId = providerId,
        secretEnc = ByteArray(0),
        fingerprint = "fp$id",
        health = health,
        enabled = enabled,
    )

    // ---------------------------------------------------------------- 计数

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

    // ---------------------------------------------------------------- 健康分布

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
        // 计数卡那条 SQL 带了 enabled = 1，所以这里也必须只算已启用的。
        // 不一致的表现是同一屏里"密钥 1"与"2 张全部可用"同时出现
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
        // allOk 为真会让那张卡画成"全部可用"，而库里一把密钥都没有
        assertTrue(!healthBreakdownOf(emptyList()).allOk)
    }

    // ---------------------------------------------------------------- 余额

    @Test
    fun `余额按币种分组求和，先舍入再相加`() {
        val summary = balanceSummaryOf(
            listOf(
                provider(1, balance = BalanceSnapshot(amount = 42.099999999999994, currency = "USD")),
                provider(2, balance = BalanceSnapshot(amount = 0.005, currency = "USD")),
                provider(3, balance = BalanceSnapshot(amount = 358.0, currency = "CNY")),
            ),
        )
        // 直接把 Double 拼进字符串的表现是首页出现 42.099999999999994；
        // 后相加再舍入则会把 0.005 也算进去（42.10 + 0.01 = 42.11 才对，先舍入是 42.10 + 0.01）
        assertEquals(
            listOf(UiMoney("CNY", "358.00"), UiMoney("USD", "42.11")),
            summary.perCurrency,
        )
    }

    @Test
    fun `查询失败的不参与合计，只计入失败家数`() {
        val summary = balanceSummaryOf(
            listOf(
                provider(1, balance = BalanceSnapshot(amount = 10.0, currency = "USD")),
                provider(2, balance = BalanceSnapshot(error = "timeout", currency = "USD")),
                provider(3),
            ),
        )
        // 把"不知道"当成 0 相加，等于把一个猜测当成余额报给用户
        assertEquals(listOf(UiMoney("USD", "10.00")), summary.perCurrency)
        assertEquals(1, summary.failedProviderCount)
    }

    @Test
    fun `余额为零与查不到是两回事`() {
        val summary = balanceSummaryOf(
            listOf(provider(1, balance = BalanceSnapshot(amount = 0.0, currency = "USD"))),
        )
        // 真的没钱了要显示 0.00，而不是被当成"没查到"从列表里消失（§9.3）
        assertEquals(listOf(UiMoney("USD", "0.00")), summary.perCurrency)
        assertEquals(0, summary.failedProviderCount)
    }

    @Test
    fun `更新时间取最新那一次`() {
        val summary = balanceSummaryOf(
            listOf(
                provider(1, balance = BalanceSnapshot(amount = 1.0, currency = "USD", checkedAt = 100)),
                provider(2, balance = BalanceSnapshot(amount = 2.0, currency = "USD", checkedAt = 900)),
            ),
        )
        assertEquals(900L, summary.updatedAt)
    }

    @Test
    fun `没查过余额时那一行不显示`() {
        val summary = balanceSummaryOf(listOf(provider(1)))
        assertTrue(summary.perCurrency.isEmpty())
        assertNull(summary.updatedAt)
    }

    // ---------------------------------------------------------------- 需要处理

    private val thresholds = mapOf("USD" to 5.0)

    @Test
    fun `瞬时失败与未探测都不进需要处理`() {
        // 红线 11：这个列表只看 health。全是 UNKNOWN 意味着"还没探测过"，
        // 而新装的应用不该首屏就满屏告警
        val items = attentionItemsOf(
            summaries = listOf(summary(provider(1), keyCount = 1)),
            healthByProvider = mapOf(1L to listOf(KeyHealth.UNKNOWN)),
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
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.KeyRejected, AttentionKind.ClientBlocked), items.map { it.kind })
        // 换一把密钥解决不了"客户端被拦"，画成红的会把人往错路上引
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
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.KeyRejected), items.map { it.kind })
    }

    @Test
    fun `额度不足与余额低不刷两行`() {
        // 两条路径指向同一件事（"这家没钱了"）：探密钥时上游说了额度不足，
        // 而余额查询回来的数字也低于阈值
        val items = attentionItemsOf(
            summaries = listOf(
                summary(provider(1, balance = BalanceSnapshot(amount = 0.45, currency = "USD"))),
            ),
            healthByProvider = mapOf(1L to listOf(KeyHealth.INSUFFICIENT)),
            thresholds = thresholds,
        )
        assertEquals(listOf(AttentionKind.LowBalance), items.map { it.kind })
    }

    @Test
    fun `密钥坏了同时余额也低，两件事各一行`() {
        val items = attentionItemsOf(
            summaries = listOf(
                summary(provider(1, balance = BalanceSnapshot(amount = 0.45, currency = "USD"))),
            ),
            healthByProvider = mapOf(1L to listOf(KeyHealth.UNAUTHORIZED)),
            thresholds = thresholds,
        )
        // 解法不同（换密钥 / 充钱），所以不能合成一行
        assertEquals(listOf(AttentionKind.KeyRejected, AttentionKind.LowBalance), items.map { it.kind })
    }

    @Test
    fun `没配阈值的币种不判低余额`() {
        // 猜一个阈值等于编一个结论（红线 15）
        val items = attentionItemsOf(
            summaries = listOf(
                summary(provider(1, balance = BalanceSnapshot(amount = 0.01, currency = "JPY"))),
            ),
            healthByProvider = emptyMap(),
            thresholds = thresholds,
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun `负余额一律算需要处理`() {
        val items = attentionItemsOf(
            summaries = listOf(
                summary(provider(1, balance = BalanceSnapshot(amount = -3.0, currency = "JPY"))),
            ),
            healthByProvider = emptyMap(),
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
            thresholds = thresholds,
        )
        // 不稳定的顺序会让用户正要点的那一行在数据刷新时跳到别处
        assertEquals(listOf("Mid", "Alpha", "Zeta"), items.map { it.providerName })
    }
}

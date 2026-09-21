package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.balance.BalanceErrorReason
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.model.ApiKey
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.screens.model.UiHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 余额与探测的**失败原因**要一路走到界面上。
 *
 * 用户报的是"安全访问令牌失效、余额查询失败、返回 401，什么提示都没有"。原因码一直
 * 存在 `api_keys.balanceError`、上游原话一直存在 `balance_raw`，但映射层只取了一个
 * `balanceFailed: Boolean` 就把它们丢了——这几条断言钉的就是"别再把它们丢掉"。
 */
class BalanceFailureMappingTest {

    private fun key(
        id: Long,
        providerId: Long,
        health: KeyHealth = KeyHealth.UNKNOWN,
        balance: BalanceSnapshot? = null,
        healthDetail: String? = null,
        httpStatus: Int? = null,
    ) = ApiKey(
        id = id,
        providerId = providerId,
        label = "key$id",
        note = "",
        secretEnc = ByteArray(0),
        fingerprint = "fp$id",
        settings = KeySettings(
            apiBaseUrl = "https://api$providerId.example.test/v1",
            apiRoot = "https://api$providerId.example.test",
        ),
        health = health,
        healthDetail = healthDetail,
        httpStatus = httpStatus,
        balance = balance,
    )

    private val unauthorized = BalanceSnapshot(
        currency = BalanceSnapshot.UNKNOWN_CURRENCY,
        error = "http 401",
        raw = """{"success":false,"message":"安全访问令牌已失效"}""",
    )

    @Test
    fun `失败快照把原因码与上游原话一起带到行上`() {
        val row = key(1, 1, balance = unauthorized).toRow(masked = "sk-…")
        assertEquals("http 401", row.balanceErrorReason)
        assertEquals("安全访问令牌已失效", row.balanceErrorHint)
        assertEquals(null, row.balance)
        assertEquals(true, row.balanceFailed)
    }

    @Test
    fun `成功时不给原因`() {
        val row = key(1, 1, balance = BalanceSnapshot(amount = 3.0, currency = "USD")).toRow("sk-…")
        assertNull(row.balanceErrorReason)
        assertNull(row.balanceErrorHint)
        assertEquals(false, row.balanceFailed)
    }

    @Test
    fun `本机解不开令牌的原因码原样带出`() {
        val row = key(
            1,
            1,
            balance = BalanceSnapshot(currency = "UNKNOWN", error = BalanceErrorReason.TOKEN_UNDECRYPTABLE),
        ).toRow("sk-…")
        assertEquals(BalanceErrorReason.TOKEN_UNDECRYPTABLE, row.balanceErrorReason)
        // 这一种压根没问到上游，所以没有"上游说的那句话"——界面不能编一句。
        assertNull(row.balanceErrorHint)
    }

    @Test
    fun `探测的上游原话只在没通过时才给`() {
        val failed = key(
            1,
            1,
            health = KeyHealth.UNAUTHORIZED,
            healthDetail = """{"error":{"message":"Invalid API key"}}""",
            httpStatus = 401,
        ).toRow("sk-…")
        assertEquals("Invalid API key", failed.probeDetail)
        assertEquals(401, failed.probeHttpStatus)

        // 400 那一种密钥是有效的（参数被拒），detail 也有内容，但绿点底下来一句
        // "上游说：xxx" 只会让人以为出了什么事。
        val ok = key(
            2,
            1,
            health = KeyHealth.OK,
            healthDetail = """{"error":{"message":"temperature too high"}}""",
            httpStatus = 400,
        ).toRow("sk-…")
        assertNull(ok.probeDetail)
        assertNull(ok.probeHttpStatus)
        assertEquals(UiHealth.Ok, ok.health)
    }

    @Test
    fun `一家里有几把没查到数得出来`() {
        val keys = listOf(
            key(1, 1, balance = BalanceSnapshot(amount = 1.0, currency = "USD")),
            key(2, 1, balance = unauthorized),
            key(3, 1, balance = unauthorized),
            key(4, 1, balance = null),
        )
        assertEquals(2, failedBalanceKeyCountOf(keys))
        // 合计只加成功那几把，所以"有合计"与"有失败"必须能同时成立。
        val aggregate = aggregateBalanceOf(keys)
        assertEquals(1.0, aggregate!!.amount!!, 1e-9)
        assertEquals(false, aggregate.failed)
    }

    @Test
    fun `部分失败时首页仍然数得到失败的把数`() {
        val keys = listOf(
            key(1, 1, balance = BalanceSnapshot(amount = 1.0, currency = "USD")),
            key(2, 1, balance = unauthorized),
        )
        val summary = keyBalanceSummaryOf(keys)
        // 整家都挂才算 failedProviderCount，这一家成了一把，所以那一格是 0——
        // 首页要是不看 failedKeyCount 就什么都不会提。
        assertEquals(0, summary.failedProviderCount)
        assertEquals(1, summary.failedKeyCount)
    }
}

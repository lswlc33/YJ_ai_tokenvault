package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.model.BalanceSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 余额历史去重：没金额不记、首次必记、与上一条相同不记、任一字段变了要记。 */
class BalanceHistoryDedupTest {

    private fun prev(amount: Double?, used: Double? = null, currency: String? = "USD") =
        BalanceSample(providerId = 1, keyId = 1, amount = amount, used = used, currency = currency, capturedAt = 1)

    @Test
    fun `没金额不记`() {
        assertFalse(shouldRecordSample(previous = null, amount = null, used = null, currency = "USD"))
        assertFalse(shouldRecordSample(previous = prev(100.0), amount = null, used = 5.0, currency = "USD"))
    }

    @Test
    fun `首次有金额必记`() {
        assertTrue(shouldRecordSample(previous = null, amount = 100.0, used = null, currency = "USD"))
    }

    @Test
    fun `与上一条完全相同不记`() {
        assertFalse(
            shouldRecordSample(previous = prev(100.0, 5.0, "USD"), amount = 100.0, used = 5.0, currency = "USD"),
        )
    }

    @Test
    fun `余额变了要记`() {
        assertTrue(
            shouldRecordSample(previous = prev(100.0, 5.0, "USD"), amount = 90.0, used = 5.0, currency = "USD"),
        )
    }

    @Test
    fun `已用变了要记（余额不变也算变化点）`() {
        assertTrue(
            shouldRecordSample(previous = prev(100.0, 5.0, "USD"), amount = 100.0, used = 8.0, currency = "USD"),
        )
    }

    @Test
    fun `币种变了要记`() {
        assertTrue(
            shouldRecordSample(previous = prev(100.0, 5.0, "USD"), amount = 100.0, used = 5.0, currency = "CNY"),
        )
    }
}

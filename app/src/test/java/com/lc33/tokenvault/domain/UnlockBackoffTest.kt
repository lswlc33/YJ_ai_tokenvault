package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解锁失败退避的阶梯（§7.2）。
 *
 * 时间一律注入（红线 20），所以这些用例不是时间敏感的。
 */
class UnlockBackoffTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `前四次不退避`() {
        for (attempts in 1..UnlockBackoff.FREE_ATTEMPTS) {
            assertEquals(
                "第 $attempts 次不该罚：手滑输错很常见",
                null,
                UnlockBackoff.nextLockedUntil(attempts, now),
            )
        }
    }

    @Test
    fun `第五次起按阶梯罚`() {
        val expected = UnlockBackoff.LADDER_SECONDS
        expected.forEachIndexed { index, seconds ->
            val attempts = UnlockBackoff.FREE_ATTEMPTS + 1 + index
            assertEquals(
                "第 $attempts 次该等 $seconds 秒",
                now + seconds * 1000L,
                UnlockBackoff.nextLockedUntil(attempts, now),
            )
        }
    }

    @Test
    fun `到顶之后一直用最后一档`() {
        val last = UnlockBackoff.LADDER_SECONDS.last()
        val far = UnlockBackoff.FREE_ATTEMPTS + UnlockBackoff.LADDER_SECONDS.size + 50
        assertEquals(now + last * 1000L, UnlockBackoff.nextLockedUntil(far, now))
    }

    @Test
    fun `剩余秒数向上取整`() {
        val backoff = UnlockBackoff(failedAttempts = 5, lockedUntilEpochMs = now + 1_500)
        // 1.5 秒要显示成 2 秒：显示 1 秒然后还等不到，比多显示一秒糟糕得多
        assertEquals(2, backoff.remainingSeconds(now))
    }

    @Test
    fun `已过期返回零而不是负数`() {
        val backoff = UnlockBackoff(failedAttempts = 5, lockedUntilEpochMs = now - 10_000)
        assertEquals("负数会让 UI 画出'还剩 -10 秒'", 0, backoff.remainingSeconds(now))
        assertFalse(backoff.isActive(now))
    }

    @Test
    fun `没在退避时剩余为零`() {
        val backoff = UnlockBackoff(failedAttempts = 2, lockedUntilEpochMs = null)
        assertEquals(0, backoff.remainingSeconds(now))
        assertFalse(backoff.isActive(now))
    }

    @Test
    fun `退避中`() {
        val backoff = UnlockBackoff(failedAttempts = 5, lockedUntilEpochMs = now + 30_000)
        assertTrue(backoff.isActive(now))
        assertEquals(30, backoff.remainingSeconds(now))
    }
}

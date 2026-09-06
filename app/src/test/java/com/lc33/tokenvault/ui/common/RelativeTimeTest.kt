package com.lc33.tokenvault.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 相对时间分档。
 *
 * 这个测试之所以能存在，是因为 `relativeBucketOf` 不读当前时间——`now` 是参数
 * （红线 20）。如果它内部调 `System.currentTimeMillis()`，这里就只能写
 * "大概是几分钟前"这种没意义的断言。
 */
class RelativeTimeTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `一分钟内算刚刚`() {
        assertEquals(RelativeBucket.JustNow, relativeBucketOf(now, now))
        assertEquals(RelativeBucket.JustNow, relativeBucketOf(now, now - 59_000))
    }

    @Test
    fun `分钟档`() {
        assertEquals(RelativeBucket.Minutes(1), relativeBucketOf(now, now - minute))
        assertEquals(RelativeBucket.Minutes(59), relativeBucketOf(now, now - 59 * minute))
    }

    @Test
    fun `小时档`() {
        assertEquals(RelativeBucket.Hours(1), relativeBucketOf(now, now - hour))
        assertEquals(RelativeBucket.Hours(23), relativeBucketOf(now, now - 23 * hour))
    }

    @Test
    fun `天档`() {
        assertEquals(RelativeBucket.Days(1), relativeBucketOf(now, now - day))
        assertEquals(RelativeBucket.Days(29), relativeBucketOf(now, now - 29 * day))
    }

    @Test
    fun `超过上限交给绝对日期`() {
        assertEquals(RelativeBucket.Absolute, relativeBucketOf(now, now - 30 * day))
        assertEquals(RelativeBucket.Absolute, relativeBucketOf(now, now - 365 * day))
    }

    /**
     * 未来时间必须单独一档。恢复一个来自另一台设备的备份、或者用户改过系统时钟，
     * 都会让时间戳落在未来；显示"0 分钟前"会让人以为刚探测过。
     */
    @Test
    fun `未来时间不伪装成刚刚`() {
        assertEquals(RelativeBucket.Future, relativeBucketOf(now, now + hour))
        // 一分钟内的时钟漂移不算未来，否则每次都要显示"时间不可信"
        assertEquals(RelativeBucket.JustNow, relativeBucketOf(now, now + 30_000))
    }

    @Test
    fun `耗时不向下取整`() {
        // 0.4 秒的探测若向下取整会显示"0 秒"，等于说"没花时间"——那是在说谎。
        assertEquals(1, durationSeconds(400))
        assertEquals(1, durationSeconds(1))
        assertEquals(0, durationSeconds(0))
        assertEquals(2, durationSeconds(1001))
        assertEquals(120, durationSeconds(120_000))
    }
}

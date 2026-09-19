package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动刷新间隔的编解码（§13.4）。
 *
 * 与 [AutoLockPolicyTest] 同一类风险：下拉按下标取值、库里存分钟数，两边一旦错位，
 * 用户选「每 15 分钟」得到的是 6 小时，而这不会报错，只会让数据一直不新。
 */
class AutoRefreshPolicyTest {

    @Test
    fun `五档与下拉一一对应，顺序不能变`() {
        // 顺序就是 `probe_auto_refresh_interval_options` 里那五项的顺序。
        assertEquals(listOf(5, 15, 30, 60, 360), AutoRefreshPolicy.OPTIONS)
    }

    @Test
    fun `每一档下标取回来都是它自己`() {
        AutoRefreshPolicy.OPTIONS.forEachIndexed { index, minutes ->
            assertEquals(minutes, AutoRefreshPolicy.at(index))
            assertEquals(index, AutoRefreshPolicy.indexOf(minutes))
        }
    }

    @Test
    fun `库里存的是分钟数不是下标`() {
        // 断言字面量而不是 `encode(15)`：这一条守的就是"存进去的到底是哪个数"。
        assertEquals("15", AutoRefreshPolicy.encode(15))
        assertEquals(15, AutoRefreshPolicy.decode("15"))
    }

    @Test
    fun `坏值与零都落回默认档`() {
        // null = 键还没写过；0 与负数是别处传错的，按 0 分钟跑等于一个不停发请求的死循环，
        // 宁可退回一小时也不要实现成"更频繁"。
        assertEquals(AutoRefreshPolicy.DEFAULT_MINUTES, AutoRefreshPolicy.decode(null))
        assertEquals(AutoRefreshPolicy.DEFAULT_MINUTES, AutoRefreshPolicy.decode(""))
        assertEquals(AutoRefreshPolicy.DEFAULT_MINUTES, AutoRefreshPolicy.decode("garbage"))
        assertEquals(AutoRefreshPolicy.DEFAULT_MINUTES, AutoRefreshPolicy.decode("0"))
        assertEquals(AutoRefreshPolicy.DEFAULT_MINUTES, AutoRefreshPolicy.decode("-5"))
    }

    @Test
    fun `表里没有的分钟数落到默认档那一枚但生效值仍是存的`() {
        // 老版本升到今天、档位表变了：下拉至少有一枚选中，而真正等的是库里那个数。
        assertEquals(AutoRefreshPolicy.indexOf(AutoRefreshPolicy.DEFAULT_MINUTES), AutoRefreshPolicy.indexOf(7))
        assertEquals(7, AutoRefreshPolicy.decode(AutoRefreshPolicy.encode(7)))
    }

    @Test
    fun `下标越界回到默认档而不是抛`() {
        assertEquals(AutoRefreshPolicy.DEFAULT_MINUTES, AutoRefreshPolicy.at(99))
    }
}

package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动锁定时限的编解码（§7.4）。
 *
 * 这几条断言值钱的地方在于：它们守的是**一个安全设置不会静默变成别的意思**。
 * 「从不」与「0 秒」在下拉里相邻，而它们的后果完全相反。
 */
class AutoLockPolicyTest {

    @Test
    fun `五档与下拉一一对应，顺序不能变`() {
        // 顺序是资源里那五项的顺序：立即 / 30 秒 / 1 分钟 / 5 分钟 / 从不。
        // 变了不会报错，表现是用户选「立即」得到 5 分钟——所以在这里钉住
        assertEquals(
            listOf(
                AutoLockTimeout.After(0),
                AutoLockTimeout.After(30),
                AutoLockTimeout.After(60),
                AutoLockTimeout.After(300),
                AutoLockTimeout.Never,
            ),
            AutoLockPolicy.OPTIONS,
        )
    }

    @Test
    fun `下标与时限来回都对得上`() {
        AutoLockPolicy.OPTIONS.forEachIndexed { index, timeout ->
            assertEquals(timeout, AutoLockPolicy.at(index))
            assertEquals(index, AutoLockPolicy.indexOf(timeout))
        }
    }

    @Test
    fun `越界的下标回到默认档而不是崩`() {
        // 越界只可能来自「资源里的条目数与这张表不一致」，那时给默认值比抛异常好：
        // 设置页至少还能打开，用户还能重新选一次
        assertEquals(AutoLockPolicy.DEFAULT, AutoLockPolicy.at(-1))
        assertEquals(AutoLockPolicy.DEFAULT, AutoLockPolicy.at(AutoLockPolicy.OPTIONS.size))
    }

    @Test
    fun `编解码往返`() {
        AutoLockPolicy.OPTIONS.forEach { timeout ->
            assertEquals(timeout, AutoLockPolicy.decode(AutoLockPolicy.encode(timeout)))
        }
    }

    @Test
    fun `存的是秒数而不是下标`() {
        // 存下标的代价是「以后在中间插一档」会让所有已存的设置悄悄改变含义，
        // 而没有任何迁移能发现它。所以这里钉住存储形态
        assertEquals("0", AutoLockPolicy.encode(AutoLockTimeout.After(0)))
        assertEquals("300", AutoLockPolicy.encode(AutoLockTimeout.After(300)))
        assertEquals("never", AutoLockPolicy.encode(AutoLockTimeout.Never))
    }

    @Test
    fun `没写过这个键时给默认值，而不是从不`() {
        // 这两件事必须分开：一个没设置过的库不该永不上锁
        assertEquals(AutoLockPolicy.DEFAULT, AutoLockPolicy.decode(null))
    }

    @Test
    fun `坏值落回默认档而不是从不`() {
        // 一段坏字符串不该让金库变成永不上锁——这是"失败往安全那一侧倒"的具体形态
        listOf("", "  ", "abc", "-1", "1e3", "60秒").forEach { bad ->
            assertEquals("解不出来时必须回到默认，输入是 <$bad>", AutoLockPolicy.DEFAULT, AutoLockPolicy.decode(bad))
        }
    }

    @Test
    fun `表里没有的秒数照旧能解出来`() {
        // 用户从旧版本升上来、或者直接改过库。生效的值是存储里那个，
        // 下拉退回默认档那一枚只是为了让界面有个选中项
        assertEquals(AutoLockTimeout.After(45), AutoLockPolicy.decode("45"))
        assertEquals(AutoLockPolicy.indexOf(AutoLockPolicy.DEFAULT), AutoLockPolicy.indexOf(AutoLockTimeout.After(45)))
    }
}

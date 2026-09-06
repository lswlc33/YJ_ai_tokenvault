package com.lc33.tokenvault.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 剪贴板自动清除秒数的编解码（§7.5）。
 *
 * 与 [AutoLockPolicy] 同一套约束：存秒数不存下标，「从不」是显式的一档（0 秒），
 * 坏值落回默认而不是「从不」。
 */
class ClipboardClearPolicyTest {

    @Test
    fun `四档与下拉一一对应，顺序不能变`() {
        // 顺序是资源里那四项的顺序：30 秒 / 60 秒 / 5 分钟 / 从不（0）。
        assertEquals(listOf(30, 60, 300, 0), ClipboardClearPolicy.OPTIONS)
    }

    @Test
    fun `下标与秒数来回都对得上`() {
        ClipboardClearPolicy.OPTIONS.forEachIndexed { index, seconds ->
            assertEquals(seconds, ClipboardClearPolicy.at(index))
            assertEquals(index, ClipboardClearPolicy.indexOf(seconds))
        }
    }

    @Test
    fun `越界的下标回到默认档而不是崩`() {
        assertEquals(ClipboardClearPolicy.DEFAULT_SECONDS, ClipboardClearPolicy.at(-1))
        assertEquals(ClipboardClearPolicy.DEFAULT_SECONDS, ClipboardClearPolicy.at(ClipboardClearPolicy.OPTIONS.size))
    }

    @Test
    fun `编解码往返`() {
        ClipboardClearPolicy.OPTIONS.forEach { seconds ->
            assertEquals(seconds, ClipboardClearPolicy.decode(ClipboardClearPolicy.encode(seconds)))
        }
    }

    @Test
    fun `存的是秒数而不是下标`() {
        // 0 = 从不（下标 3），存下标的话「插一档」会让所有已存设置悄悄变含义
        assertEquals("0", ClipboardClearPolicy.encode(0))
        assertEquals("300", ClipboardClearPolicy.encode(300))
    }

    @Test
    fun `没写过这个键时给默认 60 秒`() {
        assertEquals(ClipboardClearPolicy.DEFAULT_SECONDS, ClipboardClearPolicy.decode(null))
    }

    @Test
    fun `坏值落回默认档`() {
        listOf("", "  ", "abc", "-1", "1e3", "60秒").forEach { bad ->
            assertEquals("解不出来时必须回到默认，输入是 <$bad>", ClipboardClearPolicy.DEFAULT_SECONDS, ClipboardClearPolicy.decode(bad))
        }
    }

    @Test
    fun `表里没有的秒数照旧能解出来，下拉退回默认档`() {
        assertEquals(45, ClipboardClearPolicy.decode("45"))
        assertEquals(ClipboardClearPolicy.indexOf(ClipboardClearPolicy.DEFAULT_SECONDS), ClipboardClearPolicy.indexOf(45))
    }
}

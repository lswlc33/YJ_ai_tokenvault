package com.lc33.tokenvault.domain

import com.lc33.tokenvault.domain.PinPolicy.Weakness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弱 PIN 判定。
 *
 * 这一层不是为了提升强度（§7.2 已经承认 6 位 PIN 挡不住离线穷举），而是挡"随手设成
 * 000000"。所以用例的重点是**不要误伤**：拦得太狠会让用户在设 PIN 这一步反复被拒，
 * 而那才是真正会让人放弃加密的体验。
 */
class PinPolicyTest {

    private fun weakness(pin: String) = PinPolicy.weaknessOf(pin.toCharArray())

    @Test
    fun `全同数字被拦`() {
        assertEquals(Weakness.AllSame, weakness("000000"))
        assertEquals(Weakness.AllSame, weakness("777777"))
    }

    @Test
    fun `连续递增与递减被拦`() {
        assertEquals(Weakness.Sequential, weakness("123456"))
        assertEquals(Weakness.Sequential, weakness("987654"))
        assertEquals(Weakness.Sequential, weakness("456789"))
    }

    @Test
    fun `周期重复被拦`() {
        assertEquals(Weakness.RepeatedPattern, weakness("121212"))
        assertEquals(Weakness.RepeatedPattern, weakness("123123"))
        assertEquals(Weakness.RepeatedPattern, weakness("451451"))
    }

    @Test
    fun `普通 PIN 不被误伤`() {
        // 这几个都该放过去。拦得太狠会让用户在设 PIN 这一步反复被拒
        for (pin in listOf("284915", "719302", "100200", "192837", "530571", "112233")) {
            assertNull("$pin 不该被拦", weakness(pin))
        }
    }

    @Test
    fun `跨十位的连续不算连续`() {
        // 90 与 01 之间没有连续关系，1290 这种要放过去
        assertNull(weakness("129012"))
    }

    @Test
    fun `长口令不套用数字规则`() {
        // 换成任意长度口令之后"连续递增"没有意义，硬套会把 abcdef 拦掉
        assertNull(weakness("abcdef"))
        assertNull(weakness("passphrase-with-words"))
    }

    @Test
    fun `混了字母的输入直接放过`() {
        assertNull(weakness("1234ab"))
    }

    @Test
    fun `太短的输入不判定`() {
        // 长度校验是另一件事，不该在这里顺手做掉——两件事混在一起，错误文案就说不清了
        assertNull(weakness(""))
        assertNull(weakness("1"))
    }

    @Test
    fun `两位与四位也生效`() {
        assertEquals(Weakness.AllSame, weakness("11"))
        assertEquals(Weakness.Sequential, weakness("1234"))
        assertEquals(Weakness.RepeatedPattern, weakness("1212"))
        assertNull(weakness("2841"))
    }

    @Test
    fun `isAcceptable 与 weaknessOf 一致`() {
        assertTrue(PinPolicy.isAcceptable("284915".toCharArray()))
        assertTrue(!PinPolicy.isAcceptable("123456".toCharArray()))
    }
}

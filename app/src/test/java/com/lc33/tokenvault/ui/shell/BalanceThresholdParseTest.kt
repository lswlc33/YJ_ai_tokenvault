package com.lc33.tokenvault.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阈值输入的校验规则（[parseBalanceThreshold]）。
 *
 * 重点是那两个"看起来是数字"的非法值：`Infinity` 与 `NaN` 都能被 `toDoubleOrNull()`
 * 解析出来，只判正负会把它们放行进库，而之后每一次低额比较都得出反的结论且没有任何提示。
 */
class BalanceThresholdParseTest {

    @Test
    fun `正常的非负数都收`() {
        assertEquals(5.0, parsed("5"), 1e-9)
        assertEquals(30.0, parsed(" 30 "), 1e-9)
        assertEquals(0.0, parsed("0"), 1e-9)
        assertEquals(0.25, parsed("0.25"), 1e-9)
    }

    @Test
    fun `负数与不是数字的都不收`() {
        assertNull(parseBalanceThreshold("-1"))
        assertNull(parseBalanceThreshold(""))
        assertNull(parseBalanceThreshold("abc"))
        assertNull(parseBalanceThreshold("5.5.5"))
    }

    @Test
    fun `Infinity 与 NaN 不当成数字`() {
        // 存成 NaN：`余额 < NaN` 恒为 false，这一档永远不再判低额。
        assertNull(parseBalanceThreshold("NaN"))
        // 存成 Infinity：任何余额都低于它，全都判低额。
        assertNull(parseBalanceThreshold("Infinity"))
        assertNull(parseBalanceThreshold("-Infinity"))
    }

    /** 合法值取出来比对；非法值在这里塌成 -1，让断言读起来只关心数值本身。 */
    private fun parsed(text: String): Double = parseBalanceThreshold(text) ?: -1.0
}

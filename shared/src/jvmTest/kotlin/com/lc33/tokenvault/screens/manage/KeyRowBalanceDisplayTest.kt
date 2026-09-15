package com.lc33.tokenvault.screens.manage

import com.lc33.tokenvault.screens.model.UiHealth
import com.lc33.tokenvault.screens.model.UiKeyRow
import com.lc33.tokenvault.screens.model.UiMoney
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 密钥行右侧显示余额还是状态点的判定。
 *
 * 用户要的形态：**余额查到了、钥匙也可用**时，用余额替掉"可用"两个字——
 * 能读出余额本身就是钥匙有效的更强证据，再写一个「可用」是同一结论说两遍。
 * 但钥匙不可用时不能拿旧金额顶掉状态，否则故障看起来像正常。
 */
class KeyRowBalanceDisplayTest {

    @Test
    fun `可用且有余额时显示余额`() {
        val money = UiMoney("USD", "12.34")
        assertEquals(money, keyRowShowsBalance(row(UiHealth.Ok, money)))
    }

    @Test
    fun `密钥不可用时即使有余额也显示状态`() {
        assertNull(keyRowShowsBalance(row(UiHealth.Error, UiMoney("USD", "12.34"))))
    }

    @Test
    fun `没有余额时显示状态`() {
        assertNull(keyRowShowsBalance(row(UiHealth.Ok, null)))
    }

    @Test
    fun `未探测时显示状态`() {
        assertNull(keyRowShowsBalance(row(UiHealth.Unknown, UiMoney("USD", "0"))))
    }

    private fun row(health: UiHealth, balance: UiMoney?) = UiKeyRow(
        id = 1,
        label = "k",
        note = "",
        providerId = 1,
        masked = "sk-…",
        health = health,
        latencyMs = null,
        checkedAt = null,
        sortOrder = 0,
        balance = balance,
    )
}

package com.lc33.tokenvault.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 会员卡的娱乐倒计时（纯算术，JVM 直接钉）。 */
class MemberCountdownTest {

    @Test
    fun `没点过显示初始年份`() {
        assertEquals(2099, MemberCountdown.expiryYear(0))
    }

    @Test
    fun `每点一次减十年`() {
        assertEquals(2089, MemberCountdown.expiryYear(1))
        assertEquals(2009, MemberCountdown.expiryYear(9))
    }

    @Test
    fun `第九次还没到取消门槛`() {
        assertFalse(MemberCountdown.shouldRevert(9))
    }

    @Test
    fun `第十次刚好取消`() {
        // 边界在 `>=`：第 10 次就必须取消，不能拖到第 11 次。
        assertTrue(MemberCountdown.shouldRevert(10))
        assertTrue(MemberCountdown.shouldRevert(11))
    }
}
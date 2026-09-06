package com.lc33.tokenvault.platform

import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 自动锁定（§7.4）。
 *
 * 用**真的** [VaultSession]：这一层要断言的正是"DEK 到底被清了没有"，换成假会话就把
 * 要测的东西替换掉了。虚拟时间由 `runTest` 提供，所以这些用例不会真的等 60 秒。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession

    /** 注入给 AutoLocker 的 `elapsedRealtime`。默认与虚拟时间无关，由用例自己推。 */
    private var elapsed = 0L

    private val pin = charArrayOf('1', '2', '3', '4', '5', '6')

    /** 让 benchmark 落在"太慢"那一档，于是引导挑到下限参数，Argon2 跑得快。 */
    private fun slowClock(): () -> Long {
        var t = 0L
        return { t += 900_000_000L; t }
    }

    @Before
    fun setUp() {
        val file = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        session = VaultSession(bootStore = FileBootStore(file) { "test-device" }, nowEpochMs = { 0L })
        session.onboard(pin.copyOf(), slowClock())
        session.completeOnboarding()
        assertTrue("前置条件：用例开始时是解锁态", session.isUnlocked)
    }

    private val timeoutMs = AutoLockPolicy.DEFAULT_SECONDS * 1000L

    @Test
    fun `切后台到点就在后台锁掉`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        locker.onEnterBackground()

        advanceTimeBy(timeoutMs - 1_000)
        runCurrent()
        assertTrue("还差一秒不许锁", session.isUnlocked)

        advanceTimeBy(2_000)
        runCurrent()
        // 不等用户回来才补锁：DEK 在内存里多待一秒就多一秒被 dump 的窗口
        assertFalse("到点必须已经锁了", session.isUnlocked)
    }

    @Test
    fun `没到点就回来不锁，而且那个定时器必须失效`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        locker.onEnterBackground()
        advanceTimeBy(10_000)
        runCurrent()
        locker.onEnterForeground()

        advanceTimeBy(timeoutMs * 3)
        runCurrent()
        // 没取消的话，用户回来用了两分钟之后会被凭空踢回锁屏
        assertTrue(session.isUnlocked)
    }

    @Test
    fun `定时器被系统冻住时，回到前台自己补锁`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        locker.onEnterBackground()

        // 虚拟时间一动不动 = delay 一次都没跑（Doze / 应用待机就是这个效果），
        // 但真实世界的 elapsedRealtime 已经走过了时限
        elapsed = timeoutMs + 5_000
        locker.onEnterForeground()

        assertFalse("光靠定时器不够，回到前台必须自己再算一次", session.isUnlocked)
    }

    @Test
    fun `锁定发出一次事件，已经锁着就不再发`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        val events = mutableListOf<Unit>()
        backgroundScope.launch { locker.locked.collect { events += it } }
        runCurrent()

        locker.lockNow()
        runCurrent()
        assertEquals(1, events.size)
        assertFalse(session.isUnlocked)

        locker.lockNow()
        runCurrent()
        // 重复发的后果很具体：界面收到会把状态清成初始值，而用户此刻可能正在锁屏上输 PIN
        assertEquals("已经锁着就不该再发一次", 1, events.size)
    }

    @Test
    fun `锁定态切后台不起定时器`() = runTest {
        session.lock()
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        val events = mutableListOf<Unit>()
        backgroundScope.launch { locker.locked.collect { events += it } }
        runCurrent()

        locker.onEnterBackground()
        advanceTimeBy(timeoutMs * 2)
        runCurrent()
        assertEquals(0, events.size)
    }

    @Test
    fun `选了从不就真的不锁，回到前台也不补锁`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        locker.timeout = AutoLockTimeout.Never

        locker.onEnterBackground()
        advanceTimeBy(timeoutMs * 100)
        runCurrent()
        assertTrue("选了从不时定时器都不该起", session.isUnlocked)

        // 定时器被冻住那条补锁路径同样要认「从不」：少这一句的表现是选了从不、
        // 切后台一小时再回来，却在回来那一下被锁了
        elapsed = timeoutMs * 100
        locker.onEnterForeground()
        assertTrue("回到前台也不该补锁", session.isUnlocked)
    }

    @Test
    fun `选了立即就切后台当场锁`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        locker.timeout = AutoLockTimeout.After(0)

        locker.onEnterBackground()
        runCurrent()
        assertFalse("选了立即就不该再等", session.isUnlocked)
    }

    @Test
    fun `改短时限后切后台按新值算`() = runTest {
        val locker = AutoLocker(session, backgroundScope) { elapsed }
        locker.timeout = AutoLockTimeout.After(30)

        locker.onEnterBackground()
        advanceTimeBy(29_000)
        runCurrent()
        assertTrue(session.isUnlocked)

        advanceTimeBy(2_000)
        runCurrent()
        // 旧实现把默认的 60 秒写死在字段里，于是这里会还差 30 秒
        assertFalse("30 秒到点必须已经锁了", session.isUnlocked)
    }
}

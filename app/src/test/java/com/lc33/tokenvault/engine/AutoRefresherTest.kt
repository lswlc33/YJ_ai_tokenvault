package com.lc33.tokenvault.engine

import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
import com.lc33.tokenvault.domain.AutoRefreshPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动刷新的**时机**（§13.4 探测设置页）。
 *
 * 这里断的是"什么时候该发一轮"，不是"刷什么"——后者与仪表盘顶栏那颗按钮做的是同三件事，
 * 真要跑它就得有网络与解开的金库。所以 [RefreshRound] 换成计数的假实现，而设置用真仓库
 * 加假 DAO：从库里读出"开没开、隔多久"本来就是这条链的一半。
 *
 * 时间用 `runTest` 的虚拟时钟，所以这些用例不会真的等三十分钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRefresherTest {

    private class FakeSession(var unlocked: Boolean = true) : ProbeSession {
        override val isUnlocked: Boolean get() = unlocked
    }

    private class CountingRound : RefreshRound {
        var runs = 0
        override suspend fun run(): Boolean {
            runs++
            return true
        }
    }

    private val defaultInterval = AutoRefreshPolicy.DEFAULT_MINUTES * 60_000L
    private val fiveMinutes = 5 * 60_000L

    private fun settings() = RoomSettingsRepository(FakeAppSettingDao())

    @Test
    fun `默认就是开着的，解锁进应用那一刻发一轮`() = runTest {
        val settings = settings()
        val round = CountingRound()
        val refresher = AutoRefresher(settings, FakeSession(), round, backgroundScope)
        refresher.start()
        refresher.onUnlocked()
        runCurrent()

        // 没写过任何设置 = 开 + 默认档。这一条守的是用户那句"打开应用就该看到新数据"：
        // 默认关的话，绝大多数人一辈子都不会去设置里翻到这一项。
        assertEquals(1, round.runs)

        advanceTimeBy(defaultInterval)
        runCurrent()
        assertEquals("默认档也要按间隔继续刷", 2, round.runs)
    }

    @Test
    fun `显式关掉之后一轮也不发`() = runTest {
        val settings = settings()
        settings.setAutoRefresh(false)
        val round = CountingRound()
        AutoRefresher(settings, FakeSession(), round, backgroundScope).start()
        runCurrent()

        advanceTimeBy(100 * defaultInterval)
        runCurrent()
        assertEquals(0, round.runs)
    }

    @Test
    fun `从关拨到开时立刻刷一轮，之后按间隔继续刷`() = runTest {
        val settings = settings()
        settings.setAutoRefresh(false)
        val round = CountingRound()
        AutoRefresher(settings, FakeSession(), round, backgroundScope).start()
        runCurrent()
        assertEquals(0, round.runs)

        settings.setAutoRefresh(true)
        settings.setAutoRefreshIntervalMinutes(5)
        runCurrent()
        assertEquals("拨到开的那一刻就该刷一轮，不等满一个间隔", 1, round.runs)

        advanceTimeBy(fiveMinutes)
        runCurrent()
        assertEquals(2, round.runs)

        advanceTimeBy(fiveMinutes)
        runCurrent()
        assertEquals(3, round.runs)
    }

    @Test
    fun `锁定态不发，解锁那一刻补一轮`() = runTest {
        val settings = settings()
        val session = FakeSession(unlocked = false)
        val round = CountingRound()
        val refresher = AutoRefresher(settings, session, round, backgroundScope)
        refresher.start()
        refresher.onUnlocked()
        runCurrent()

        settings.setAutoRefreshIntervalMinutes(5)
        advanceTimeBy(3 * fiveMinutes)
        runCurrent()
        // 默认是开的，所以"锁定态一轮都不发"完全靠这道闸：探测要 reveal 密钥，
        // 锁定时发出去只会得到一轮全失败，还往审计日志里灌错误。
        assertEquals(0, round.runs)

        // 用户解锁 = 他意义上的"打开了应用"，这一刷由 AppRoot 那一句补上。
        session.unlocked = true
        refresher.onUnlocked()
        runCurrent()
        assertEquals(1, round.runs)
    }

    @Test
    fun `只改间隔不再发一轮`() = runTest {
        val settings = settings()
        settings.setAutoRefresh(false)
        val round = CountingRound()
        AutoRefresher(settings, FakeSession(), round, backgroundScope).start()
        runCurrent()

        settings.setAutoRefresh(true)
        runCurrent()
        assertEquals(1, round.runs)

        settings.setAutoRefreshIntervalMinutes(5)
        runCurrent()
        // 改的是"多久一次"，不是"现在来一次"。
        assertEquals(1, round.runs)

        advanceTimeBy(fiveMinutes)
        runCurrent()
        assertEquals("新间隔从改完这一刻起算", 2, round.runs)
    }

    @Test
    fun `运行中关掉开关后定时器停住`() = runTest {
        val settings = settings()
        val round = CountingRound()
        AutoRefresher(settings, FakeSession(), round, backgroundScope).start()
        runCurrent()

        settings.setAutoRefreshIntervalMinutes(5)
        advanceTimeBy(fiveMinutes)
        runCurrent()
        assertEquals(2, round.runs)

        settings.setAutoRefresh(false)
        runCurrent()
        advanceTimeBy(10 * fiveMinutes)
        runCurrent()
        assertEquals("关掉之后还发一轮就是没停干净", 2, round.runs)
    }

    @Test
    fun `start 重复调用不起第二条定时器`() = runTest {
        val settings = settings()
        val round = CountingRound()
        val refresher = AutoRefresher(settings, FakeSession(), round, backgroundScope)
        refresher.start()
        refresher.start()
        runCurrent()

        settings.setAutoRefreshIntervalMinutes(5)
        advanceTimeBy(fiveMinutes)
        runCurrent()
        // 两端入口 + 转屏重来都可能重复调；起两条循环就等于每个间隔发两轮请求。
        assertEquals(2, round.runs)
    }
}

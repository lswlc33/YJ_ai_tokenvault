package com.lc33.tokenvault.engine

import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
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
 * 时间用 `runTest` 的虚拟时钟，所以这些用例不会真的等五分钟。
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

    private val fiveMinutes = 5 * 60_000L

    @Test
    fun `默认关着一轮到点也不发`() = runTest {
        val round = CountingRound()
        AutoRefresher(
            RoomSettingsRepository(FakeAppSettingDao()),
            FakeSession(),
            round,
            backgroundScope,
        ).start()
        runCurrent()

        advanceTimeBy(100 * fiveMinutes)
        runCurrent()
        // 自动路径要往每一家供应商发真请求，所以"没写过"必须是关：
        // 一次升级就把这个行为打开是不请自来的。
        assertEquals(0, round.runs)
    }

    @Test
    fun `拨到开时立刻刷一轮，之后按间隔继续刷`() = runTest {
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        val round = CountingRound()
        AutoRefresher(settings, FakeSession(), round, backgroundScope).start()
        runCurrent()

        settings.setAutoRefresh(true)
        settings.setAutoRefreshIntervalMinutes(5)
        runCurrent()
        assertEquals("拨到开的那一刻就该刷一轮", 1, round.runs)

        advanceTimeBy(fiveMinutes)
        runCurrent()
        assertEquals(2, round.runs)

        advanceTimeBy(fiveMinutes)
        runCurrent()
        assertEquals(3, round.runs)
    }

    @Test
    fun `锁定态不发，解锁那一刻补一轮`() = runTest {
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        val session = FakeSession(unlocked = false)
        val round = CountingRound()
        val refresher = AutoRefresher(settings, session, round, backgroundScope)
        refresher.start()
        refresher.onUnlocked()
        runCurrent()

        settings.setAutoRefresh(true)
        settings.setAutoRefreshIntervalMinutes(5)
        advanceTimeBy(3 * fiveMinutes)
        runCurrent()
        // 锁定态发这一轮只会得到一轮全失败（探测要 reveal 密钥），还往审计日志里灌错误。
        assertEquals(0, round.runs)

        // 用户解锁 = 他意义上的"打开了应用"，这一刷由 AppRoot 那一句补上。
        session.unlocked = true
        refresher.onUnlocked()
        runCurrent()
        assertEquals(1, round.runs)
    }

    @Test
    fun `只改间隔不再发一轮`() = runTest {
        val settings = RoomSettingsRepository(FakeAppSettingDao())
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
        assertEquals("新间隔要从改完这一刻起算", 2, round.runs)
    }

    @Test
    fun `关掉开关后定时器停住`() = runTest {
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        val round = CountingRound()
        AutoRefresher(settings, FakeSession(), round, backgroundScope).start()
        runCurrent()

        settings.setAutoRefresh(true)
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
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        val round = CountingRound()
        val refresher = AutoRefresher(settings, FakeSession(), round, backgroundScope)
        refresher.start()
        refresher.start()
        runCurrent()

        settings.setAutoRefresh(true)
        settings.setAutoRefreshIntervalMinutes(5)
        advanceTimeBy(fiveMinutes)
        runCurrent()
        // 两端入口 + 测试里重复调；起两条循环就等于每个间隔发两轮请求。
        assertEquals(2, round.runs)
    }
}

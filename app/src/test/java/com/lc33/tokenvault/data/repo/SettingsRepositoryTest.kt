package com.lc33.tokenvault.data.repo

import app.cash.turbine.test
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.probe.ProbeClassifier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 设置仓库。
 *
 * 这一版只有自动锁定时限一项，所以用例也只围着它转。三条断言分别对应三种真会发生的错：
 * 键还没写过时给错默认值、存了下标而不是秒数、别的设置项写入把这条流吵醒一次。
 */
class SettingsRepositoryTest {

    private lateinit var dao: FakeAppSettingDao
    private lateinit var repo: RoomSettingsRepository

    @Before
    fun setUp() {
        dao = FakeAppSettingDao()
        repo = RoomSettingsRepository(dao)
    }

    @Test
    fun `没写过时发默认值而不是从不`() = runTest {
        repo.observeAutoLockTimeout().test {
            // 发 Never 的后果是新装的应用永不自动锁定，而设置页画着「1 分钟后」
            assertEquals(AutoLockPolicy.DEFAULT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `写了立刻能读回来`() = runTest {
        repo.setAutoLockTimeout(AutoLockTimeout.After(0))
        repo.observeAutoLockTimeout().test {
            assertEquals(AutoLockTimeout.After(0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `从不也存得住`() = runTest {
        repo.setAutoLockTimeout(AutoLockTimeout.Never)
        repo.observeAutoLockTimeout().test {
            assertEquals(AutoLockTimeout.Never, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `落库的是秒数，不是下拉的下标`() = runTest {
        repo.setAutoLockTimeout(AutoLockTimeout.After(300))

        // 下标 3 与秒数 300 都是"合法"的存储内容，编译期分不出来，所以在这里钉住。
        // 存下标的代价是以后插一档就会让所有已存的设置悄悄改变含义
        assertEquals("300", dao.rows.single().value)
    }

    @Test
    fun `同一个键写两次只有一行`() = runTest {
        repo.setAutoLockTimeout(AutoLockTimeout.After(30))
        repo.setAutoLockTimeout(AutoLockTimeout.After(300))

        assertEquals(1, dao.rows.size)
        assertEquals("300", dao.rows.single().value)
    }

    @Test
    fun `别的设置项写入不会让这条流重复发同一个值`() = runTest {
        repo.setAutoLockTimeout(AutoLockTimeout.After(30))
        repo.observeAutoLockTimeout().test {
            assertEquals(AutoLockTimeout.After(30), awaitItem())

            // 这个仓库订阅的是整张表，所以任何一项设置写入都会让底下那条 Flow 再发一次。
            // 不去重的表现是每改一次别的开关，AutoLocker 就被重设一次时限
            dao.put(com.lc33.tokenvault.data.entity.AppSettingEntity(key = "somethingElse", value = "x"))
            expectNoEvents()

            repo.setAutoLockTimeout(AutoLockTimeout.Never)
            assertEquals(AutoLockTimeout.Never, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- 余额阈值

    @Test
    fun `阈值没写过时发默认值`() = runTest {
        repo.observeBalanceThresholds().test {
            assertEquals(BalanceSnapshot.DEFAULT_THRESHOLDS, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `阈值写了立刻能读回来`() = runTest {
        val custom = mapOf("USD" to 10.0, "CNY" to 100.0)
        repo.setBalanceThresholds(custom)
        repo.observeBalanceThresholds().test {
            assertEquals(custom, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `阈值存的是 JSON 不是可读性差的拼串`() = runTest {
        repo.setBalanceThresholds(mapOf("USD" to 10.5, "CNY" to 0.0))

        // 阈值不是秘密，但也要能被人看懂；更重要的是读回来必须严格等于写进去的值，
        // 不能因为拼串分隔符跟金额里的字符撞了而损坏。
        val raw = dao.rows.single { it.key == "balanceThresholds" }.value
        assertEquals("{\"USD\":10.5,\"CNY\":0.0}", raw)
        repo.observeBalanceThresholds().test {
            assertEquals(mapOf("USD" to 10.5, "CNY" to 0.0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- 客户端关键词

    @Test
    fun `关键词没写过时发默认表`() = runTest {
        repo.observeClientKeywords().test {
            assertEquals(ProbeClassifier.DEFAULT_CLIENT_KEYWORDS, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `关键词写了立刻能读回来，顺序不变`() = runTest {
        val custom = listOf("foo bar", "unauthorized client", "中文关键词")
        repo.setClientKeywords(custom)
        repo.observeClientKeywords().test {
            assertEquals(custom, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `关键词存的是 JSON 数组`() = runTest {
        repo.setClientKeywords(listOf("a", "b"))
        val raw = dao.rows.single { it.key == "clientKeywords" }.value
        assertEquals("[\"a\",\"b\"]", raw)
    }

    // ---------------------------------------------------------------- 代理

    @Test
    fun `代理没写过时发空串`() = runTest {
        repo.observeProxy().test {
            assertEquals("", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `代理写了立刻能读回来`() = runTest {
        repo.setProxy("127.0.0.1:7890")
        repo.observeProxy().test {
            assertEquals("127.0.0.1:7890", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- 前台空闲 / 屏幕关闭锁定

    @Test
    fun `空闲锁定没写过时默认关`() = runTest {
        repo.observeIdleLock().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `空闲锁定开了能读回来`() = runTest {
        repo.setIdleLock(true)
        repo.observeIdleLock().test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `坏值落回关，不落回开`() = runTest {
        // 直接往 DAO 写一个坏值，读方向必须单向容错到「关」——开锁是安全那一侧，
        // 坏数据不该让金库突然多出一条会自动锁定的路
        dao.put(com.lc33.tokenvault.data.entity.AppSettingEntity(key = "idleLockSeconds", value = "garbage"))
        repo.observeIdleLock().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `屏幕关闭锁定没写过时默认关`() = runTest {
        repo.observeLockOnScreenOff().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `屏幕关闭锁定开了能读回来`() = runTest {
        repo.setLockOnScreenOff(true)
        repo.observeLockOnScreenOff().test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

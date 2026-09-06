package com.lc33.tokenvault.data.repo

import app.cash.turbine.test
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
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
}

package com.lc33.tokenvault.data.repo

import app.cash.turbine.test
import com.lc33.tokenvault.domain.AutoLockPolicy
import com.lc33.tokenvault.domain.AutoLockTimeout
import com.lc33.tokenvault.domain.DefaultProbeSettings
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

    // ---------------------------------------------------------------- 客户端嗅探

    @Test
    fun `嗅探没写过时默认开`() = runTest {
        repo.observeSniffClientProfile().test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `嗅探关了能读回来`() = runTest {
        repo.setSniffClientProfile(false)
        repo.observeSniffClientProfile().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `嗅探坏值落回开，不落回关`() = runTest {
        // 与空闲锁定相反：嗅探的「开」是增强可用性那一侧（客户端被拦时自动兜底），
        // 坏数据不该让探测失去这条兜底，所以读方向容错到「开」。
        dao.put(com.lc33.tokenvault.data.entity.AppSettingEntity(key = "sniffClientProfile", value = "garbage"))
        repo.observeSniffClientProfile().test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- 剪贴板自动清除

    @Test
    fun `剪贴板清除没写过时默认 60 秒`() = runTest {
        repo.observeClipboardClearSeconds().test {
            assertEquals(60, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `剪贴板清除写了能读回来`() = runTest {
        repo.setClipboardClearSeconds(300)
        repo.observeClipboardClearSeconds().test {
            assertEquals(300, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `剪贴板清除存的是秒数不是下标`() = runTest {
        // 存 0 = 从不（下标 3），存 30 = 下标 0。断言读回的是秒数本身，
        // 下标只是 UI 层的映射（ClipboardClearPolicy.indexOf）。
        repo.setClipboardClearSeconds(0)
        repo.observeClipboardClearSeconds().test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `剪贴板清除坏值落回默认 60`() = runTest {
        dao.put(com.lc33.tokenvault.data.entity.AppSettingEntity(key = "clipboardClearSeconds", value = "garbage"))
        repo.observeClipboardClearSeconds().test {
            assertEquals(60, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- 更新渠道

    @Test
    fun `更新渠道没写过时默认正式版`() = runTest {
        repo.observeUpdateChannel().test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `更新渠道选了 nightly 能读回来`() = runTest {
        repo.setUpdateChannel(1)
        repo.observeUpdateChannel().test {
            assertEquals(1, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `更新渠道坏值落回正式版`() = runTest {
        // 越界的下标（比如将来加了渠道又删掉）与坏字符串都落回 0=正式版——pre-release
        // 不是默认那一侧。
        dao.put(com.lc33.tokenvault.data.entity.AppSettingEntity(key = "updateChannel", value = "99"))
        repo.observeUpdateChannel().test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- 新建默认探测值

    @Test
    fun `默认探测值没写过时发全默认`() = runTest {
        repo.observeDefaultProbeSettings().test {
            assertEquals(DefaultProbeSettings(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `默认探测值写了能读回来`() = runTest {
        val custom = DefaultProbeSettings(reachability = false, keys = true, balance = false, models = true)
        repo.setDefaultProbeSettings(custom)
        repo.observeDefaultProbeSettings().test {
            assertEquals(custom, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `默认探测值存的是 JSON 对象，键名是字段名`() = runTest {
        repo.setDefaultProbeSettings(DefaultProbeSettings())
        val raw = dao.rows.single { it.key == "defaultProbe" }.value
        assertEquals("{\"reachability\":true,\"keys\":true,\"balance\":true,\"models\":false}", raw)
    }

    @Test
    fun `默认探测值缺字段时用默认值补，坏数据落回全默认`() = runTest {
        // 只写一个字段：其余字段该落回默认，而不是抛异常拖垮新建流程。
        dao.put(
            com.lc33.tokenvault.data.entity.AppSettingEntity(
                key = "defaultProbe",
                value = "{\"models\":true}",
            ),
        )
        repo.observeDefaultProbeSettings().test {
            assertEquals(
                DefaultProbeSettings(reachability = true, keys = true, balance = true, models = true),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }

        // 整个 JSON 坏了：落回全默认。
        dao.put(
            com.lc33.tokenvault.data.entity.AppSettingEntity(key = "defaultProbe", value = "not-json"),
        )
        repo.observeDefaultProbeSettings().test {
            assertEquals(DefaultProbeSettings(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

package com.lc33.tokenvault.engine

import com.lc33.tokenvault.data.entity.AppSettingEntity
import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
import com.lc33.tokenvault.domain.HttpConcurrencyPolicy
import com.lc33.tokenvault.net.ConcurrencyGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 并发档位的"设置 → 运行时"那一段（§13.4）。
 *
 * 这一条链最容易出的错不是算错数，而是**根本没人推**：把订阅挂在探测设置页的 ViewModel 上，
 * 用户没进过那一页，闸就一辈子停在构造时的默认值，而页面上显示的是库里的另一档。
 * 所以这里断的是"进程级订阅会把库里的值推到闸上"，用的还是真仓库 + 假 DAO——
 * 从 `app_settings` 读出档位本来就是这条链的一半。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpConcurrencyApplierTest {

    private fun dao() = FakeAppSettingDao()

    @Test
    fun `没写过设置时闸被推到默认档`() = runTest {
        // 闸故意以一个错的初值构造：证明是流把它推到位，不是它本来就对。
        val gate = ConcurrencyGate(1)
        HttpConcurrencyApplier(RoomSettingsRepository(dao()), gate, backgroundScope).start()
        runCurrent()
        assertEquals(HttpConcurrencyPolicy.DEFAULT, gate.currentLimit())
    }

    @Test
    fun `改了设置立刻换档，不用重启也不用发请求`() = runTest {
        val dao = dao()
        val settings = RoomSettingsRepository(dao)
        val gate = ConcurrencyGate()
        HttpConcurrencyApplier(settings, gate, backgroundScope).start()
        runCurrent()
        assertEquals(8, gate.currentLimit())

        settings.setMaxConcurrency(32)
        runCurrent()
        assertEquals(32, gate.currentLimit())
    }

    @Test
    fun `库里是坏值时落回默认而不是把闸关掉`() = runTest {
        // 0 是"一个请求都别想出去"。写路径不会产出它（下拉只有五档），但备份恢复、
        // 老版本、手工改库都可能，所以读方向必须自己兜住。
        val dao = dao()
        dao.put(AppSettingEntity(key = "maxConcurrency", value = "0"))
        val gate = ConcurrencyGate(16)
        HttpConcurrencyApplier(RoomSettingsRepository(dao), gate, backgroundScope).start()
        runCurrent()
        assertEquals(HttpConcurrencyPolicy.DEFAULT, gate.currentLimit())
    }

    @Test
    fun `没 start 之前不会偷偷读库`() = runTest {
        // DiGraphSmokeTest 的前提是"只解析定义、不查库"。构造一个 applier 就开订阅、
        // 就把整张 app_settings 读一遍，那条前提就破了。
        val gate = ConcurrencyGate(5)
        HttpConcurrencyApplier(RoomSettingsRepository(dao()), gate, backgroundScope)
        runCurrent()
        assertEquals(5, gate.currentLimit())
    }

    @Test
    fun `start 重复调用不起第二条订阅`() = runTest {
        // 两端入口各调一次是设计（Android onCreate / iOS initIosApp），但同一端重复调
        // 不能多开一条：多一条只是多读一遍库，还会让"谁最后写"变成调度问题。
        val dao = dao()
        val settings = RoomSettingsRepository(dao)
        val gate = ConcurrencyGate()
        val applier = HttpConcurrencyApplier(settings, gate, backgroundScope)
        applier.start()
        applier.start()
        runCurrent()
        settings.setMaxConcurrency(4)
        runCurrent()
        assertEquals(4, gate.currentLimit())
    }
}

package com.lc33.tokenvault.engine

import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.entity.ModelChangeEntity
import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.FakeAuditLogDao
import com.lc33.tokenvault.data.repo.FakeBalanceHistoryDao
import com.lc33.tokenvault.data.repo.FakeModelChangeDao
import com.lc33.tokenvault.data.repo.FakeProbeRunDao
import com.lc33.tokenvault.data.repo.RoomAuditLogRepository
import com.lc33.tokenvault.data.repo.RoomBalanceHistoryRepository
import com.lc33.tokenvault.data.repo.RoomModelChangeRepository
import com.lc33.tokenvault.data.repo.RoomProbeRunRepository
import com.lc33.tokenvault.data.repo.RoomSettingsRepository
import com.lc33.tokenvault.domain.model.LogRetention
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保留期清理：设置 → 清理 → DAO 这条链，跑的是真仓库 + 假 DAO。
 *
 * 不把 [LogMaintenance] 单独拎出来测的原因是：它会出错的地方正是"设置里存的
 * 值"与"清理时算的天数"对不上，而这两件事分在两个仓库里。所以这一组用真仓库串起来。
 */
class LogMaintenanceTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private suspend fun FakeAuditLogDao.seed(at: Long, message: String) {
        insert(AuditLogEntity(at = at, level = "info", category = "vault", message = message))
    }

    private fun audit(dao: FakeAuditLogDao) = RoomAuditLogRepository(dao, Redactor()) { now }

    private suspend fun messages(repo: RoomAuditLogRepository) =
        repo.observeRecent(10).first().map { it.message }

    @Test
    fun `默认保留七天，更早的日志在启动时清掉`() = runTest {
        val dao = FakeAuditLogDao()
        dao.seed(now - 8 * day, "old")
        dao.seed(now - 6 * day, "recent")
        val repo = audit(dao)

        // 没写过任何设置：默认就是 7 天，不需要用户先去设置页点一下。
        LogMaintenance(
            RoomSettingsRepository(FakeAppSettingDao()),
            repo,
            RoomProbeRunRepository(FakeProbeRunDao()),
            RoomBalanceHistoryRepository(FakeBalanceHistoryDao()) { now },
            RoomModelChangeRepository(FakeModelChangeDao()),
            { now },
        ).run()

        assertEquals(listOf("recent"), messages(repo))
    }

    @Test
    fun `永久保留时一条也不删`() = runTest {
        val dao = FakeAuditLogDao()
        dao.seed(now - 800 * day, "very old")
        val repo = audit(dao)
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        settings.setLogRetention(LogRetention.FOREVER)

        // probeRuns 用真仓库 + 假 DAO：条数上限那条路径也要被这条链走一遍。
        LogMaintenance(
            settings, repo, RoomProbeRunRepository(FakeProbeRunDao()),
            RoomBalanceHistoryRepository(FakeBalanceHistoryDao()) { now },
            RoomModelChangeRepository(FakeModelChangeDao()), { now },
        ).run()

        assertEquals(listOf("very old"), messages(repo))
    }

    @Test
    fun `改成三十天后按新值清理，而不是仍按七天`() = runTest {
        val dao = FakeAuditLogDao()
        // 二十天前：7 天会删掉它，30 天必须留着。
        dao.seed(now - 20 * day, "twenty days")
        val repo = audit(dao)
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        settings.setLogRetention(LogRetention.THIRTY_DAYS)

        // probeRuns 用真仓库 + 假 DAO：条数上限那条路径也要被这条链走一遍。
        LogMaintenance(
            settings, repo, RoomProbeRunRepository(FakeProbeRunDao()),
            RoomBalanceHistoryRepository(FakeBalanceHistoryDao()) { now },
            RoomModelChangeRepository(FakeModelChangeDao()), { now },
        ).run()

        assertEquals(listOf("twenty days"), messages(repo))
    }

    /**
     * 上下架流水的保留：一年之前的不留。
     *
     * 「模型变化」页最长只看 90 天，一年是留给"去年这时候上的新"还能查得到的余量。
     */
    @Test
    fun `流水里一年之前的那批清掉`() = runTest {
        val dao = FakeModelChangeDao()
        dao.insertAll(
            listOf(
                change("over-a-year", now - 400 * day),
                change("recent", now - 3 * day),
            ),
        )
        LogMaintenance(
            RoomSettingsRepository(FakeAppSettingDao()),
            audit(FakeAuditLogDao()),
            RoomProbeRunRepository(FakeProbeRunDao()),
            RoomBalanceHistoryRepository(FakeBalanceHistoryDao()) { now },
            RoomModelChangeRepository(dao),
            { now },
        ).run()

        assertEquals(listOf("recent"), dao.rows.map { it.modelId })
    }

    /**
     * 流水的条数上限，与天数各拦一类：一张有几百个模型的 Key 一年之内就能攒出几万行，
     * 按时间裁拦不住它。留最新的（按 `at`、再按 `id`），删最老的。
     *
     * 5000 这个数写在这条断言里而不是引 `LogMaintenance` 的常量：那道上限是私有的，
     * 而它一旦被改动，这条测试就该红一次让人去看一眼是不是真要改口径。
     */
    @Test
    fun `流水超出条数上限时留最新的`() = runTest {
        val dao = FakeModelChangeDao()
        dao.insertAll((0..5000).map { index -> change("m$index", now - 2 * day) })
        LogMaintenance(
            RoomSettingsRepository(FakeAppSettingDao()),
            audit(FakeAuditLogDao()),
            RoomProbeRunRepository(FakeProbeRunDao()),
            RoomBalanceHistoryRepository(FakeBalanceHistoryDao()) { now },
            RoomModelChangeRepository(dao),
            { now },
        ).run()

        assertEquals("多塞一条就该删掉最老那条", 5000L, dao.rows.size.toLong())
        assertEquals("删的必须是最先写进去的那条", "m1", dao.rows.first().modelId)
    }

    private fun change(modelId: String, at: Long) = ModelChangeEntity(
        providerId = 1,
        keyId = 1,
        modelId = modelId,
        protocol = "chat",
        kind = "added",
        at = at,
    )
}

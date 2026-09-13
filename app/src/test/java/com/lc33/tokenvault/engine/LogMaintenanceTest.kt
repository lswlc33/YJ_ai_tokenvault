package com.lc33.tokenvault.engine

import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.data.entity.AuditLogEntity
import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.FakeAuditLogDao
import com.lc33.tokenvault.data.repo.RoomAuditLogRepository
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
        LogMaintenance(RoomSettingsRepository(FakeAppSettingDao()), repo, { now }).run()

        assertEquals(listOf("recent"), messages(repo))
    }

    @Test
    fun `永久保留时一条也不删`() = runTest {
        val dao = FakeAuditLogDao()
        dao.seed(now - 800 * day, "very old")
        val repo = audit(dao)
        val settings = RoomSettingsRepository(FakeAppSettingDao())
        settings.setLogRetention(LogRetention.FOREVER)

        LogMaintenance(settings, repo, { now }).run()

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

        LogMaintenance(settings, repo, { now }).run()

        assertEquals(listOf("twenty days"), messages(repo))
    }
}

package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.domain.model.AuditEntry
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.domain.repo.AuditLogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写路径审计：用户改了东西就该在日志里留下一条。
 *
 * 这一组钉的是"接上了没有、级别对不对、敏感值有没有漏进去"，脱敏规则本身在
 * [AuditLogRepositoryTest] 里验。用例只挑三条有代表性的写路径（供应商 / 分组 /
 * 日志自身失败），其余仓库接的是同一个 [recordSafe]，接线的形状一致。
 */
class RepoAuditTest {

    private var now = 1_700_000_000_000L

    private fun auditRepo(dao: FakeAuditLogDao = FakeAuditLogDao()) =
        RoomAuditLogRepository(dao, Redactor()) { now }

    private suspend fun RoomAuditLogRepository.messages(): List<String> =
        observeRecent(20).first().map { it.message }

    @Test
    fun `供应商的新增改名与删除都会留痕`() = runTest {
        val audit = auditRepo()
        val repo = RoomProviderRepository(FakeProviderDao(), { now }, audit)

        val id = repo.save(Provider(name = "Agent Router"))
        now += 1000
        repo.save(repo.find(id)!!.copy(name = "改名了"))
        now += 1000
        repo.delete(id)

        val entries = audit.observeRecent(20).first()
        assertEquals(
            listOf("provider deleted", "provider updated", "provider added"),
            entries.map { it.message },
        )
        // 删除是 WARN：它连带删掉密钥与账号，用户回头查日志时要一眼看出破坏性动作。
        assertEquals(LogLevel.WARN, entries.first().level)
        assertEquals(LogCategory.VAULT, entries.first().category)
        assertEquals(id, entries.first().providerId)
    }

    @Test
    fun `组合操作只记数量与分组 id，不记名字`() = runTest {
        val audit = auditRepo()
        val repo = RoomProviderRepository(FakeProviderDao(), { now }, audit)
        val first = repo.save(Provider(name = "a"))
        val second = repo.save(Provider(name = "b"))

        now += 1000
        repo.reorder(listOf(second, first))
        now += 1000
        repo.setGroup(listOf(first, second), groupId = 7)

        val entries = audit.observeRecent(20).first()
        // 前面两次新增也各自留了一条，这里只看这两步组合操作。
        assertEquals(listOf("providers regrouped", "providers reordered"), entries.take(2).map { it.message })
        // detail 里只有数量与组 id：日志页是给人看的，不是第二份供应商表。
        assertEquals("count=2 groupId=7", entries.first().detail)
        assertEquals("count=2", entries[1].detail)
    }

    @Test
    fun `分组的新建改名排序与删除都会留痕`() = runTest {
        val audit = auditRepo()
        val repo = RoomGroupRepository(FakeGroupDao(), audit)

        val id = repo.add(" 科研 ")
        now += 1000
        repo.rename(id, "科研专用")
        now += 1000
        repo.reorder(listOf(id))
        now += 1000
        repo.delete(id)

        assertEquals(
            listOf("group deleted", "groups reordered", "group renamed", "group added"),
            audit.messages(),
        )
    }

    @Test
    fun `日志写失败不影响用户操作`() = runTest {
        // 日志是旁路：磁盘满、表被锁都不该把一次成功的保存变成用户眼里的失败。
        val broken = object : AuditLogRepository {
            override suspend fun record(
                level: LogLevel,
                category: LogCategory,
                message: String,
                detail: String?,
                providerId: Long?,
                keyId: Long?,
            ) {
                throw IllegalStateException("disk full")
            }

            override fun observeRecent(limit: Int, minLevel: LogLevel) = flowOf(emptyList<AuditEntry>())

            override suspend fun trimOlderThan(before: Long) = Unit

            override suspend fun clear() = Unit
        }
        val dao = FakeProviderDao()
        val repo = RoomProviderRepository(dao, { now }, broken)

        val id = repo.save(Provider(name = "Agent Router"))

        assertEquals(id, dao.rows.single().id)
        assertTrue(repo.find(id) != null)
    }
}

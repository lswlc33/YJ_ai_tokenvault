package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.domain.model.LogCategory
import com.lc33.tokenvault.domain.model.LogLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 日志仓库：脱敏（红线 32）在 [record] 入库前收口。
 *
 * 验证 `sk-` / `Bearer` / 邮箱三类凭据形态在落库前被替换成占位符，而不是原样进库。
 */
class AuditLogRepositoryTest {

    private lateinit var dao: FakeAuditLogDao
    private lateinit var repo: RoomAuditLogRepository

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dao = FakeAuditLogDao()
        repo = RoomAuditLogRepository(dao, Redactor(), { now })
    }

    @Test
    fun `sk 密钥被脱敏`() = runTest {
        repo.record(
            LogLevel.ERROR,
            LogCategory.PROBE,
            message = "key sk-abcdefghijklmnopqrstuvwxyz123456 rejected",
        )
        val entry = repo.observeRecent(10).first().single()
        assertTrue(!entry.message.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(entry.message.contains("<redacted>"))
    }

    @Test
    fun `bearer 令牌被脱敏`() = runTest {
        repo.record(
            LogLevel.ERROR,
            LogCategory.BALANCE,
            message = "Authorization: Bearer abcdefghijklmnop",
        )
        val entry = repo.observeRecent(10).first().single()
        assertTrue(!entry.message.contains("abcdefghijklmnop"))
        assertTrue(entry.message.contains("<redacted>"))
    }

    @Test
    fun `邮箱被脱敏`() = runTest {
        repo.record(
            LogLevel.WARN,
            LogCategory.ACCOUNT,
            message = "username me@example.com failed",
        )
        val entry = repo.observeRecent(10).first().single()
        assertTrue(!entry.message.contains("me@example.com"))
    }

    @Test
    fun `detail 同样脱敏`() = runTest {
        repo.record(
            LogLevel.ERROR,
            LogCategory.HTTP,
            message = "upstream error",
            detail = """{"api_key":"sk-secret-in-json-123456789"}""",
        )
        val entry = repo.observeRecent(10).first().single()
        assertTrue(!entry.detail!!.contains("sk-secret-in-json"))
    }

    @Test
    fun `按最低等级筛选`() = runTest {
        repo.record(LogLevel.DEBUG, LogCategory.VAULT, message = "debug")
        repo.record(LogLevel.INFO, LogCategory.VAULT, message = "info")
        repo.record(LogLevel.ERROR, LogCategory.VAULT, message = "error")

        val visible = repo.observeRecent(10, LogLevel.INFO).first()

        assertEquals(listOf("error", "info"), visible.map { it.message })
    }

    @Test
    fun `按保留时间删除旧日志`() = runTest {
        repo.record(LogLevel.INFO, LogCategory.VAULT, message = "old")
        val oldAt = now
        now += 8L * 24 * 60 * 60 * 1000
        repo.record(LogLevel.INFO, LogCategory.VAULT, message = "new")

        repo.trimOlderThan(oldAt + 1)

        assertEquals(listOf("new"), repo.observeRecent(10).first().map { it.message })
    }

    @Test
    fun `日志倒序且带分类`() = runTest {
        repo.record(LogLevel.INFO, LogCategory.LOCK, message = "first")
        now += 1000
        repo.record(LogLevel.WARN, LogCategory.VAULT, message = "second")
        val entries = repo.observeRecent(10).first()
        assertEquals(2, entries.size)
        assertEquals("second", entries[0].message)
        assertEquals(LogCategory.VAULT, entries[0].category)
        assertEquals(LogLevel.WARN, entries[0].level)
    }
}

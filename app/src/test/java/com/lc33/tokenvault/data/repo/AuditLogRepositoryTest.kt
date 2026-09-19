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

    /**
     * 失败路径的 `responseBody`。`HttpEngine` 在 catch 里写的是裸 `t.message`，而 Ktor 把
     * 出错的 `Url` 原样拼进了那句话（实测文案 `Socket timeout has expired [url=https://…, …]`）。
     * 地址里带 `user:pass@` 是合法输入，于是口令会从这一个字段进库。
     *
     * 为什么单独盯这个字段：日志**列表**那一道查询（`AuditLogSummary`）压根不取报文，
     * 所以"日志页看不到"并不代表它不在——`audit_log` 整张表会进备份包，口令就是这么被
     * 上传到 WebDAV 的。收口点在仓库这一层（红线 32），所以钉在这里而不是 HttpEngine。
     */
    @Test
    fun `异常文案里的地址口令进不了库`() = runTest {
        repo.record(
            LogLevel.ERROR,
            LogCategory.HTTP,
            message = "http GET https://127.0.0.1:8080/v1/models failed",
            detail = "ConnectTimeoutException",
            requestUrl = "https://127.0.0.1:8080/v1/models",
            responseBody = "Socket timeout has expired [url=https://admin:Sup3rPassw0rd@127.0.0.1:8080" +
                "/v1/models, socket_timeout=20000] ms",
        )
        val id = repo.observeRecent(10).first().single().id
        val full = repo.findById(id)
        assertTrue("没读到那条完整日志（列表查询不取报文，必须按 id 回读）", full != null)
        val stored = "${full!!.message}${full.detail}${full.requestUrl}${full.responseBody}"
        assertTrue("口令进了日志表：$stored", !stored.contains("Sup3rPassw0rd"))
        // host 与路径要留着：不然"哪一家超时了"这条信息就没了。
        assertTrue("擦过头了，地址整段没了：$stored", full.responseBody!!.contains("127.0.0.1:8080/v1/models"))
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

package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.Protocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomModelRepositoryTest {

    private var now = 1_700_000_000_000L

    private fun repo(dao: FakeModelDao = FakeModelDao()) =
        RoomModelRepository(dao, ImmediateTransactions()) { now }

    @Test
    fun `首次发现写入 discovered 并记录协议`() = runTest {
        val dao = FakeModelDao()
        repo(dao).applyDiscovered(
            providerId = 1,
            protocol = Protocol.CHAT,
            modelIds = listOf("gpt-5.6-sol"),
        )
        repo(dao).applyDiscovered(
            providerId = 1,
            protocol = Protocol.ANTHROPIC,
            modelIds = listOf("claude-opus-5"),
        )

        assertEquals(2, dao.rows.size)
        dao.rows.forEach { row ->
            assertEquals("discovered", row.source)
            assertEquals(row.protocol, row.discoveredVia)
            assertTrue(row.enabled)
            assertEquals(now, row.lastSeenAt)
        }
    }

    @Test
    fun `再次发现 touch 存在项并停用消失项`() = runTest {
        val dao = FakeModelDao()
        val repository = repo(dao)
        repository.applyDiscovered(
            providerId = 1,
            protocol = Protocol.CHAT,
            modelIds = listOf("gpt-5.6-sol", "gpt-4o"),
        )
        now += 1_000
        repository.applyDiscovered(
            providerId = 1,
            protocol = Protocol.CHAT,
            modelIds = listOf("gpt-5.6-sol"),
        )

        val rows = dao.rows.associateBy { it.modelId }
        assertTrue(rows.getValue("gpt-5.6-sol").enabled)
        assertEquals(now, rows.getValue("gpt-5.6-sol").lastSeenAt)
        assertFalse(rows.getValue("gpt-4o").enabled)
    }

    @Test
    fun `手动模型与其它协议发现项不被停用`() = runTest {
        val dao = FakeModelDao()
        val repository = repo(dao)
        repository.add(1, "my-custom-model", Protocol.CHAT)
        repository.applyDiscovered(1, Protocol.ANTHROPIC, listOf("claude-opus-5"))
        now += 1_000
        repository.applyDiscovered(1, Protocol.CHAT, emptyList())

        val rows = dao.rows.associateBy { it.modelId }
        assertTrue(rows.getValue("my-custom-model").enabled)
        assertEquals("manual", rows.getValue("my-custom-model").source)
        assertTrue(rows.getValue("claude-opus-5").enabled)
    }
}

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
        RoomModelRepository(dao, ImmediateTransactions(), now = { now })

    @Test
    fun `首次发现写入 discovered 并记录协议`() = runTest {
        val dao = FakeModelDao()
        repo(dao).applyDiscovered(
            providerId = 1,
            keyId = 10,
            protocol = Protocol.CHAT,
            modelIds = listOf("gpt-5.6-sol"),
        )
        repo(dao).applyDiscovered(
            providerId = 1,
            keyId = 10,
            protocol = Protocol.ANTHROPIC,
            modelIds = listOf("claude-opus-5"),
        )

        assertEquals(2, dao.rows.size)
        dao.rows.forEach { row ->
            assertEquals("discovered", row.source)
            assertEquals(row.protocol, row.discoveredVia)
            assertEquals(now, row.lastSeenAt)
        }
    }

    @Test
    fun `再次发现 touch 存在项并删掉消失项`() = runTest {
        val dao = FakeModelDao()
        val repository = repo(dao)
        repository.applyDiscovered(
            providerId = 1,
            keyId = 10,
            protocol = Protocol.CHAT,
            modelIds = listOf("gpt-5.6-sol", "gpt-4o"),
        )
        now += 1_000
        repository.applyDiscovered(
            providerId = 1,
            keyId = 10,
            protocol = Protocol.CHAT,
            modelIds = listOf("gpt-5.6-sol"),
        )

        val rows = dao.rows.associateBy { it.modelId }
        assertEquals(1, dao.rows.size)
        assertEquals(now, rows.getValue("gpt-5.6-sol").lastSeenAt)
        assertFalse(rows.containsKey("gpt-4o"))
    }

    @Test
    fun `手动模型与其它协议发现项不被删除`() = runTest {
        val dao = FakeModelDao()
        val repository = repo(dao)
        repository.add(1, 10, "my-custom-model", Protocol.CHAT)
        repository.applyDiscovered(1, 10, Protocol.ANTHROPIC, listOf("claude-opus-5"))
        now += 1_000
        repository.applyDiscovered(1, 10, Protocol.CHAT, emptyList())

        val rows = dao.rows.associateBy { it.modelId }
        assertEquals("manual", rows.getValue("my-custom-model").source)
        assertTrue(rows.containsKey("claude-opus-5"))
    }
}

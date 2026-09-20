package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.model.BalanceSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 余额历史仓库：record 走去重（[com.lc33.tokenvault.balance.shouldRecordSample]）后落库，
 * observeAll 按时间升序读回。用假 DAO（JVM 上没有真 Room，见 [FakeDaos]）。
 */
class BalanceHistoryRepositoryTest {

    private fun snapshot(amount: Double?, used: Double? = null, at: Long) =
        BalanceSnapshot(amount = amount, used = used, currency = "USD", checkedAt = at)

    @Test
    fun `连续相同的余额只落一行`() = runTest {
        val dao = FakeBalanceHistoryDao()
        val repo = RoomBalanceHistoryRepository(dao) { 0L }

        repo.record(providerId = 1, keyId = 1, snapshot = snapshot(100.0, at = 1))
        repo.record(providerId = 1, keyId = 1, snapshot = snapshot(100.0, at = 2)) // 相同，跳过
        repo.record(providerId = 1, keyId = 1, snapshot = snapshot(80.0, at = 3))  // 变了，记

        val rows = repo.observeAll().first()
        assertEquals(2, rows.size)
        assertEquals(listOf(100.0, 80.0), rows.map { it.amount })
        assertEquals(listOf(1L, 3L), rows.map { it.capturedAt })
    }

    @Test
    fun `失败快照（无金额）不落库`() = runTest {
        val dao = FakeBalanceHistoryDao()
        val repo = RoomBalanceHistoryRepository(dao) { 0L }

        repo.record(providerId = 1, keyId = 1, snapshot = snapshot(amount = null, at = 5))

        assertEquals(0, dao.count())
    }

    @Test
    fun `不同 Key 各自去重、互不影响`() = runTest {
        val dao = FakeBalanceHistoryDao()
        val repo = RoomBalanceHistoryRepository(dao) { 0L }

        repo.record(providerId = 1, keyId = 1, snapshot = snapshot(100.0, at = 1))
        repo.record(providerId = 1, keyId = 2, snapshot = snapshot(100.0, at = 1)) // 另一把 Key 的首次，要记

        assertEquals(2, dao.count())
    }

    @Test
    fun `checkedAt 缺失时用注入的 now`() = runTest {
        val dao = FakeBalanceHistoryDao()
        val repo = RoomBalanceHistoryRepository(dao) { 999L }

        repo.record(providerId = 1, keyId = 1, snapshot = BalanceSnapshot(amount = 50.0, currency = "USD", checkedAt = null))

        assertEquals(999L, repo.observeAll().first().single().capturedAt)
    }
}

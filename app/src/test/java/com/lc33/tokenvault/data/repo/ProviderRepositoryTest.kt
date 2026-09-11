package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.model.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ProviderRepositoryTest {

    private lateinit var dao: FakeProviderDao
    private lateinit var repo: RoomProviderRepository
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        dao = FakeProviderDao()
        repo = RoomProviderRepository(dao, now = { now })
    }

    private fun draft(name: String = "Agent Router") = Provider(name = name)

    @Test
    fun `新增与保存合集信息`() = runTest {
        val id = repo.save(draft())
        assertNotNull(repo.find(id))
        assertEquals("Agent Router", dao.rows.single().name)

        now += 60_000
        repo.save(repo.find(id)!!.copy(name = "改名了", note = "备注"))
        assertEquals("改名了", dao.rows.single().name)
        assertEquals("备注", dao.rows.single().note)
    }

    @Test
    fun `保存不覆盖 createdAt，但会推进 updatedAt`() = runTest {
        val id = repo.save(draft())
        val created = dao.rows.single().createdAt

        now += 60_000
        repo.save(repo.find(id)!!.copy(name = "改名了"))

        assertEquals(created, dao.rows.single().createdAt)
        assertEquals(now, dao.rows.single().updatedAt)
    }

    @Test
    fun `官网连通性结果单独落库`() = runTest {
        val id = repo.save(draft())
        repo.updateWebsiteStatus(id, 123, now, null)

        val provider = repo.find(id)!!
        assertEquals(123L, provider.website.latencyMs)
        assertEquals(now, provider.website.checkedAt)
        assertNull(provider.website.error)

        repo.updateWebsiteStatus(id, null, now + 1, "http 500")
        assertEquals("http 500", repo.find(id)!!.website.error)
    }
}

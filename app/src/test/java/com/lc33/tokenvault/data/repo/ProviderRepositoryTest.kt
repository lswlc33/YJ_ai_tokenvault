package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 供应商仓库。
 *
 * 重点只有一个：**余额令牌那一列的三档语义**（不动 / 清掉 / 换新的）。
 * 它值得单独测，因为写错的那一种表现最阴——"每次改备注顺手把令牌清掉"，
 * 而用户下次看余额时只会看到查询失败，没人会想到是编辑页干的。
 */
class ProviderRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var dao: FakeProviderDao
    private lateinit var repo: RoomProviderRepository

    private var now = 1_700_000_000_000L

    private fun slowClock(): () -> Long {
        var t = 0L
        return { t += 900_000_000L; t }
    }

    @Before
    fun setUp() {
        val file = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        session = VaultSession(bootStore = FileBootStore(file) { "test-device" }, nowEpochMs = { now })
        session.onboard("194057".toCharArray(), slowClock())
        session.completeOnboarding()
        dao = FakeProviderDao()
        repo = RoomProviderRepository(
            dao = dao,
            cipher = FieldCipher(session, SecretBox()),
            transactions = ImmediateTransactions(),
            now = { now },
        )
    }

    private fun draft(name: String = "Agent Router") = Provider(
        name = name,
        apiBaseUrl = "https://api.example.test/v1",
        apiRoot = "https://api.example.test",
    )

    private fun openToken(id: Long): String {
        val envelope = dao.rows.first { it.id == id }.balanceTokenEnc!!
        val plain = session.withFieldKey { key ->
            SecretBox().open(envelope, key, FieldAad.of("providers", id, "balanceTokenEnc"), "test")
        }
        return String(plain, Charsets.UTF_8)
    }

    private companion object {
        const val TOKEN = "tv-unit-test-token-0001"
        const val NEW_TOKEN = "tv-unit-test-token-0002"
    }

    @Test
    fun `新增时令牌绑的是新分配的那个 id`() = runTest {
        val id = repo.save(draft(), TOKEN.toCharArray())

        // 绑错 id 的表现是"存进去了但下次读不出来"，而那时已经没有明文可以重存
        assertEquals(TOKEN, openToken(id))
    }

    @Test
    fun `保存时不传令牌就不动那一列`() = runTest {
        val id = repo.save(draft(), TOKEN.toCharArray())
        val stored = dao.rows.single().balanceTokenEnc!!

        // 编辑页手上没有那份密文（它压根没读过），所以领域对象里的 balanceTokenEnc 是 null。
        // 照抄它就等于每次改备注都把令牌清掉
        val reloaded = repo.find(id)!!
        repo.save(reloaded.copy(note = "改了个备注", balanceTokenEnc = null), balanceToken = null)

        assertTrue(stored.contentEquals(dao.rows.single().balanceTokenEnc))
        assertEquals(TOKEN, openToken(id))
        assertEquals("改了个备注", dao.rows.single().note)
    }

    @Test
    fun `传空数组表示用户要清掉令牌`() = runTest {
        val id = repo.save(draft(), TOKEN.toCharArray())

        repo.save(repo.find(id)!!, balanceToken = CharArray(0))

        // 清掉必须是 null 而不是"一段空明文的密文"：后者读出来是空串，
        // 于是余额适配器会拿空令牌去发请求，拿到 401 并把它当成"令牌无效"
        assertNull(dao.rows.single().balanceTokenEnc)
    }

    @Test
    fun `换令牌之后解出来是新的那个`() = runTest {
        val id = repo.save(draft(), TOKEN.toCharArray())

        repo.save(repo.find(id)!!, balanceToken = NEW_TOKEN.toCharArray())

        assertEquals(NEW_TOKEN, openToken(id))
    }

    @Test
    fun `保存不覆盖 createdAt，但会推进 updatedAt`() = runTest {
        val id = repo.save(draft())
        val created = dao.rows.single().createdAt

        now += 60_000
        repo.save(repo.find(id)!!.copy(name = "改名了"))

        val row = dao.rows.single()
        assertEquals(created, row.createdAt)
        assertEquals(now, row.updatedAt)
    }

    @Test
    fun `新增时不传令牌，那一列是空的`() = runTest {
        val id = repo.save(draft())
        assertNull(dao.rows.single().balanceTokenEnc)
        assertNotNull(repo.find(id))
    }
}

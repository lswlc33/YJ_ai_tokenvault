package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 平台账号仓库。
 *
 * 重点：**用户名与密码都加密、且 AAD 各自绑定**。两列 AAD 相同的话，用户名密文能被
 * 搬到密码列上照样解开——那是红线 24 在这张表上的具体表达。
 */
class ProviderAccountRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var dao: FakeProviderAccountDao
    private lateinit var repo: RoomProviderAccountRepository

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
        dao = FakeProviderAccountDao()
        repo = RoomProviderAccountRepository(
            dao = dao,
            cipher = FieldCipher(session, SecretBox()),
            transactions = ImmediateTransactions(),
            now = { now },
        )
    }

    private fun open(column: String, id: Long): String {
        val row = dao.rows.first { it.id == id }
        val envelope = if (column == "usernameEnc") row.usernameEnc else row.passwordEnc
        val plain = session.withFieldKey { key ->
            SecretBox().open(envelope!!, key, FieldAad.of("provider_accounts", id, column), "test")
        }
        return String(plain, Charsets.UTF_8)
    }

    @Test
    fun `用户名密码都加密且能解回`() = runTest {
        val id = repo.add(
            providerId = 1,
            label = "公司主号",
            username = "company@example.com".toCharArray(),
            password = "Secret123".toCharArray(),
            loginUrl = "https://example.com",
        )

        assertEquals("company@example.com", open("usernameEnc", id))
        assertEquals("Secret123", open("passwordEnc", id))
    }

    @Test
    fun `用户名与密码 AAD 不同所以密文不可互换`() = runTest {
        val id = repo.add(
            providerId = 1,
            label = "x",
            username = "u".toCharArray(),
            password = "p".toCharArray(),
            loginUrl = null,
        )
        val row = dao.rows.single()

        // 把用户名密文搬到密码列，用密码列的 AAD 解，必须失败
        val moved = runCatching {
            session.withFieldKey { key ->
                SecretBox().open(row.usernameEnc!!, key, FieldAad.of("provider_accounts", id, "passwordEnc"), "test")
            }
        }
        assertNull(moved.getOrNull())
    }

    @Test
    fun `只记用户名不记密码也合法`() = runTest {
        val id = repo.add(
            providerId = 1,
            label = "只记一半",
            username = "u@example.com".toCharArray(),
            password = null,
            loginUrl = null,
        )
        val row = dao.rows.single()
        assertNotNull(row.usernameEnc)
        assertNull(row.passwordEnc)
        assertNotNull(row.usernameFp)
    }

    @Test
    fun `用户名指纹用于去重`() = runTest {
        repo.add(1, "a", "same@example.com".toCharArray(), "p1".toCharArray(), null)
        repo.add(1, "b", "same@example.com".toCharArray(), "p2".toCharArray(), null)
        // 假 DAO 不实现唯一约束，但指纹必须一致（真库里靠它拦重复）
        val fps = dao.rows.map { it.usernameFp }.toSet()
        assertEquals(1, fps.size)
    }

    @Test
    fun `revealUsername 解回明文`() = runTest {
        val id = repo.add(
            providerId = 1,
            label = "x",
            username = "company@example.com".toCharArray(),
            password = null,
            loginUrl = null,
        )
        val plain = repo.revealUsername(id)
        assertNotNull(plain)
        assertEquals("company@example.com", String(plain!!))
        plain.zeroize()
    }

    @Test
    fun `没记用户名时 revealUsername 返回 null`() = runTest {
        val id = repo.add(
            providerId = 1,
            label = "只记密码",
            username = null,
            password = "p".toCharArray(),
            loginUrl = null,
        )
        assertNull(repo.revealUsername(id))
    }
}

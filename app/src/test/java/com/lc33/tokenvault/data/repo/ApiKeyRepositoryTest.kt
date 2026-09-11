package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.DecryptionFailedException
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiKeyRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var keyDao: FakeApiKeyDao
    private lateinit var settingsDao: FakeKeySettingsDao
    private lateinit var repo: RoomApiKeyRepository

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
        assertTrue(session.isUnlocked)
        settingsDao = FakeKeySettingsDao()
        keyDao = FakeApiKeyDao(settingsDao)
        repo = RoomApiKeyRepository(
            dao = keyDao,
            settingsDao = settingsDao,
            cipher = FieldCipher(session, SecretBox()),
            transactions = ImmediateTransactions(),
            now = { now },
        )
    }

    private companion object {
        const val SECRET = "tv-unit-test-secret-0001"
        const val OTHER_SECRET = "tv-unit-test-secret-0002"
    }

    private fun settings() = KeySettings(
        apiBaseUrl = "https://api.example.test/v1",
        apiRoot = "https://api.example.test",
    )

    private suspend fun add(
        providerId: Long = 1,
        label: String = "主力",
        note: String = "",
        secret: String = SECRET,
    ) = repo.add(providerId, label, note, secret.toCharArray(), settings())

    @Test
    fun `新增之后能解出同一份明文并保存行为配置`() = runTest {
        val id = add(note = "月度限额较高")

        assertEquals(SECRET, String(repo.reveal(id)))
        assertEquals("月度限额较高", repo.find(id)!!.note)
        assertEquals(settings(), repo.find(id)!!.settings)

        val stored = keyDao.rows.single()
        assertFalse(String(stored.secretEnc, Charsets.UTF_8) == SECRET)
        assertTrue(stored.secretEnc.size > SECRET.length)
    }

    @Test
    fun `密文绑在那一行上，搬到另一行就解不开`() = runTest {
        val first = add(label = "a")
        val second = add(label = "b", secret = OTHER_SECRET)
        val stolen = keyDao.rows.first { it.id == first }.secretEnc

        keyDao.setSecret(second, stolen, "whatever", now)
        assertThrows(DecryptionFailedException::class.java) { runBlockingReveal(second) }
    }

    private fun runBlockingReveal(id: Long) = kotlinx.coroutines.runBlocking { repo.reveal(id) }

    @Test
    fun `锁定态新增会抛，而且库里不留半行`() = runTest {
        session.lock()
        assertThrows(VaultLockedException::class.java) {
            kotlinx.coroutines.runBlocking { add(label = "x") }
        }
        assertTrue(keyDao.rows.isEmpty())
        assertTrue(settingsDao.rows.isEmpty())
    }

    @Test
    fun `排序代替默认，第一张排最前`() = runTest {
        val first = add(label = "a")
        val second = add(label = "b", secret = OTHER_SECRET)

        assertEquals(0, repo.find(first)!!.sortOrder)
        assertEquals(1, repo.find(second)!!.sortOrder)

        repo.reorder(1, listOf(second, first))
        assertEquals(0, repo.find(second)!!.sortOrder)
        assertEquals(1, repo.find(first)!!.sortOrder)
    }

    @Test
    fun `同一份明文算出同一个指纹，不同明文不同`() = runTest {
        val a = add(providerId = 1, label = "a")
        val b = add(providerId = 2, label = "b")
        val c = add(providerId = 1, label = "c", secret = OTHER_SECRET)

        assertEquals(repo.find(a)!!.fingerprint, repo.find(b)!!.fingerprint)
        assertNotEquals(repo.find(a)!!.fingerprint, repo.find(c)!!.fingerprint)
    }

    @Test
    fun `换明文之后指纹跟着换，旧明文解不出来`() = runTest {
        val id = add(label = "a")
        val old = repo.find(id)!!.fingerprint

        repo.replaceSecret(id, OTHER_SECRET.toCharArray())

        assertEquals(OTHER_SECRET, String(repo.reveal(id)))
        assertNotEquals(old, repo.find(id)!!.fingerprint)
    }

    @Test
    fun `只改名称备注与排序不动密文`() = runTest {
        val id = add(label = "旧标签", note = "旧备注")
        val before = repo.find(id)!!.secretEnc

        repo.updateMeta(
            repo.find(id)!!.copy(label = "新标签", note = "新备注", secretEnc = ByteArray(0)),
        )

        val after = repo.find(id)!!
        assertEquals("新标签", after.label)
        assertEquals("新备注", after.note)
        assertArrayEquals(before, after.secretEnc)
        assertEquals(SECRET, String(repo.reveal(id)))
    }

    @Test
    fun `余额令牌按 Key 加密且可解出`() = runTest {
        val token = "balance-token".toCharArray()
        val id = repo.add(
            providerId = 1,
            label = "newapi",
            note = "",
            secret = SECRET.toCharArray(),
            settings = settings().copy(balanceKind = com.lc33.tokenvault.domain.BalanceKind.NEWAPI),
            balanceToken = token,
        )

        val revealed = repo.revealBalanceToken(id)!!
        assertEquals("balance-token", String(revealed))
        revealed.zeroize()
    }

    @Test
    fun `解密用的 AAD 就是表名行号列名那一串`() = runTest {
        val id = add(label = "a")
        val envelope = keyDao.rows.single { it.id == id }.secretEnc
        val plain = session.withFieldKey { key ->
            SecretBox().open(envelope, key, FieldAad.of("api_keys", id, "secretEnc"), "test")
        }
        assertEquals(SECRET, String(plain, Charsets.UTF_8))
    }
}

private fun CharArray.zeroize() = fill(' ')

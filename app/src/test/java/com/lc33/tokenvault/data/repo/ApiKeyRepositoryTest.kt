package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.DecryptionFailedException
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.VaultLockedException
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 密钥仓库（§14.3 测试 3 的仓库那一半）。
 *
 * **用真的 [VaultSession] + 真的 Argon2 / HKDF / AES-GCM**，只把 DAO 换成假的
 * （[FakeApiKeyDao]，理由见那边的 KDoc）。这一层最容易错的地方恰好是"密文到底绑在
 * 哪一行上"，用假加密就把要测的东西替换掉了。
 *
 * 引导参数被 [slowClock] 压到下限，所以整套是秒级的。
 */
class ApiKeyRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var dao: FakeApiKeyDao
    private lateinit var repo: RoomApiKeyRepository

    private var now = 1_700_000_000_000L

    /** 让 benchmark 落在"太慢"那一档，于是引导挑到下限参数，Argon2 跑得快。 */
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
        assertTrue("前置条件：用例开始时是解锁态", session.isUnlocked)
        dao = FakeApiKeyDao()
        repo = RoomApiKeyRepository(
            dao = dao,
            cipher = FieldCipher(session, SecretBox()),
            transactions = ImmediateTransactions(),
            now = { now },
        )
    }

    private companion object {
        const val SECRET = "tv-unit-test-secret-0001"
        const val OTHER_SECRET = "tv-unit-test-secret-0002"
    }

    @Test
    fun `新增之后能解出同一份明文`() = runTest {
        val id = repo.add(providerId = 1, label = "主力", secret = SECRET.toCharArray())

        val revealed = repo.reveal(id)
        assertEquals(SECRET, String(revealed))
        // 落库的那一份必须是密文：等于明文说明加密整条被跳过了
        val stored = dao.rows.single()
        assertFalse(String(stored.secretEnc, Charsets.UTF_8) == SECRET)
        assertTrue("密文比明文长（封套头 14 字节 + tag 16 字节）", stored.secretEnc.size > SECRET.length)
    }

    @Test
    fun `密文绑在那一行上，搬到另一行就解不开`() = runTest {
        val first = repo.add(1, "a", SECRET.toCharArray())
        val second = repo.add(1, "b", OTHER_SECRET.toCharArray())
        val stolen = dao.rows.first { it.id == first }.secretEnc

        // 把第一行的密文整块拷到第二行——没有 AAD 的话这一步是"可以解开"的，
        // 于是 b 就"变成"了 a（红线 24 防的正是这个）
        dao.setSecret(second, stolen, "whatever", now)

        assertThrows(DecryptionFailedException::class.java) { runBlockingReveal(second) }
    }

    /** `assertThrows` 要一个非 suspend 的 lambda。 */
    private fun runBlockingReveal(id: Long) = kotlinx.coroutines.runBlocking { repo.reveal(id) }

    @Test
    fun `锁定态新增会抛，而且库里不留半行`() = runTest {
        session.lock()

        assertThrows(VaultLockedException::class.java) {
            kotlinx.coroutines.runBlocking { repo.add(1, "x", SECRET.toCharArray()) }
        }
        // 指纹是在插入之前算的，所以抛在那一步 = 一行都没插进去。
        // 反过来（先插入再算指纹）会留下一行密文为空的记录，而它永远解不开
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `第一张自动成为默认，删掉之后下一张顶上`() = runTest {
        val first = repo.add(1, "a", SECRET.toCharArray())
        assertTrue("第一张必须自动设默认（红线 6.3）", dao.findById(first)!!.isDefault)

        val second = repo.add(1, "b", OTHER_SECRET.toCharArray())
        assertFalse("第二张不该抢默认", dao.findById(second)!!.isDefault)

        repo.delete(first)
        // 不顶人的话四个余额适配器会静默失效——它们都读"默认那张"
        assertTrue(dao.findById(second)!!.isDefault)
    }

    @Test
    fun `同一份明文算出同一个指纹，不同明文不同`() = runTest {
        val a = repo.add(1, "a", SECRET.toCharArray())
        val b = repo.add(2, "b", SECRET.toCharArray())
        val c = repo.add(1, "c", OTHER_SECRET.toCharArray())

        // 相同才可能被 (providerId, fingerprint) 唯一索引挡住；跨供应商也相同，
        // 因为指纹是"这台设备上这个值"的函数，与它挂在谁下面无关
        assertEquals(dao.findById(a)!!.fingerprint, dao.findById(b)!!.fingerprint)
        assertNotEquals(dao.findById(a)!!.fingerprint, dao.findById(c)!!.fingerprint)
    }

    @Test
    fun `换明文之后指纹跟着换，旧明文解不出来`() = runTest {
        val id = repo.add(1, "a", SECRET.toCharArray())
        val oldFingerprint = dao.findById(id)!!.fingerprint

        repo.replaceSecret(id, OTHER_SECRET.toCharArray())

        assertEquals(OTHER_SECRET, String(repo.reveal(id)))
        // 指纹不跟着换的表现是去重永远拿旧值比，于是新明文能被重复录入
        assertNotEquals(oldFingerprint, dao.findById(id)!!.fingerprint)
    }

    @Test
    fun `只改标签不动密文`() = runTest {
        val id = repo.add(1, "旧标签", SECRET.toCharArray())
        val before = dao.findById(id)!!.secretEnc

        repo.updateMeta(repo.find(id)!!.copy(label = "新标签", secretEnc = ByteArray(0)))

        // 刻意传一个 secretEnc 被清空的领域对象：走 @Update 整行替换的话密文就没了，
        // 而 setMeta 那条语句连密文列都没提到
        assertEquals("新标签", dao.findById(id)!!.label)
        assertTrue(before.contentEquals(dao.findById(id)!!.secretEnc))
        assertEquals(SECRET, String(repo.reveal(id)))
    }

    @Test
    fun `解密用的 AAD 就是表名行号列名那一串`() = runTest {
        val id = repo.add(1, "a", SECRET.toCharArray())
        val envelope = dao.findById(id)!!.secretEnc

        // 手工用同一个 AAD 解一遍：这条断言把"AAD 拼错了但两边一致"的情况也挡掉——
        // 那种错编译通过、自己解自己也通过，只有对着约定的字符串比才看得出来
        val plain = session.withFieldKey { key ->
            SecretBox().open(envelope, key, FieldAad.of("api_keys", id, "secretEnc"), "test")
        }
        assertEquals(SECRET, String(plain, Charsets.UTF_8))
    }
}

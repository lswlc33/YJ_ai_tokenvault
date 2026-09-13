package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.crypto.Redactor
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.domain.repo.combined
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 删除后撤销。
 *
 * 这些用例锁的是三件靠 review 看不出来的事：
 *
 * 1. **按原主键写回。** 换了 id 就会让字段级密文的 AAD（`表:主键:列`）对不上，
 *    解开时报 `DecryptionFailedException`——而这只在真的解一次明文时才会暴露。
 * 2. **密文原样恢复仍可解密。** 撤销的卖点就是这个：不用重新加密，用户拿回的
 *    还是原来那把钥匙。
 * 3. **冲突时返回 false 而不是抛异常。** 撤销必须安静地失败，不能把 App 崩掉。
 *
 * **假 DAO 不实现外键级联**（见 FakeDaos 的注释：CASCADE 只有真设备能验），所以
 * 级联相关的用例在调用仓库删除之后，会自己把子行删掉来模拟级联已经发生。
 * 这样验的仍然是生产路径：快照在仓库内部、删除之前拍下，撤销时按原样写回。
 */
class UndoDeletionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var providerDao: FakeProviderDao
    private lateinit var keyDao: FakeApiKeyDao
    private lateinit var settingsDao: FakeKeySettingsDao
    private lateinit var modelDao: FakeModelDao
    private lateinit var accountDao: FakeProviderAccountDao
    private lateinit var groupDao: FakeGroupDao
    private lateinit var profileDao: FakeClientProfileDao
    private lateinit var keys: RoomApiKeyRepository
    private lateinit var providers: RoomProviderRepository
    private lateinit var models: RoomModelRepository
    private lateinit var accounts: RoomProviderAccountRepository

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

        val cipher = FieldCipher(session, SecretBox())
        val transactions = ImmediateTransactions()

        settingsDao = FakeKeySettingsDao()
        keyDao = FakeApiKeyDao(settingsDao)
        modelDao = FakeModelDao()
        accountDao = FakeProviderAccountDao()
        providerDao = FakeProviderDao()
        groupDao = FakeGroupDao()
        profileDao = FakeClientProfileDao()

        val audit = RoomAuditLogRepository(FakeAuditLogDao(), Redactor(), { now })
        val restorer = UndoRestorer(
            providerDao = providerDao,
            apiKeyDao = keyDao,
            settingsDao = settingsDao,
            modelDao = modelDao,
            accountDao = accountDao,
            groupDao = groupDao,
            profileDao = profileDao,
            transactions = transactions,
        )
        keys = RoomApiKeyRepository(
            dao = keyDao,
            settingsDao = settingsDao,
            cipher = cipher,
            transactions = transactions,
            now = { now },
            audit = audit,
            restorer = restorer,
        )
        providers = RoomProviderRepository(
            dao = providerDao,
            now = { now },
            audit = audit,
            restorer = restorer,
        )
        models = RoomModelRepository(
            dao = modelDao,
            transactions = transactions,
            now = { now },
            audit = audit,
            restorer = restorer,
        )
        accounts = RoomProviderAccountRepository(
            dao = accountDao,
            cipher = cipher,
            transactions = transactions,
            now = { now },
            audit = audit,
            restorer = restorer,
        )
    }

    private fun settings() = KeySettings(
        apiBaseUrl = "https://api.example.test/v1",
        apiRoot = "https://api.example.test",
    )

    private suspend fun newProvider(name: String = "测试供应商"): Long =
        providers.save(
            com.lc33.tokenvault.domain.model.Provider(
                name = name,
                createdAt = now,
                updatedAt = now,
            ),
        )

    @Test
    fun `删除密钥后撤销，密钥按原 id 回来且明文仍能解开`() = runTest {
        val providerId = newProvider()
        val keyId = keys.add(
            providerId = providerId,
            label = "KeyOne",
            note = "",
            secret = "tv-undo-secret-000001".toCharArray(),
            settings = settings(),
            balanceToken = null,
        )
        val original = keys.find(keyId)
        assertNotNull(original)

        val undo = keys.delete(keyId)
        assertNotNull("删除应返回撤销句柄", undo)
        assertEquals(null, keys.find(keyId))

        assertTrue("撤销应成功", undo!!.undo())

        val restored = keys.find(keyId)
        assertNotNull("密钥应已恢复", restored)
        // 原主键必须一致：AAD 绑的是它。
        assertEquals(keyId, restored!!.id)
        assertEquals("KeyOne", restored.label)
        // 关键断言：密文没有重新加密，却还能解出原来的明文。
        assertEquals("tv-undo-secret-000001", keys.reveal(keyId).concatToString())
    }

    @Test
    fun `删除密钥后撤销，挂在它上面的模型也一起回来`() = runTest {
        val providerId = newProvider()
        val keyId = keys.add(
            providerId = providerId,
            label = "KeyOne",
            note = "",
            secret = "tv-undo-secret-000002".toCharArray(),
            settings = settings(),
            balanceToken = null,
        )
        val modelId = models.add(
            providerId = providerId,
            keyId = keyId,
            modelId = "gpt-4o",
            protocol = com.lc33.tokenvault.domain.Protocol.CHAT,
            needsReview = false,
        )

        val undo = keys.delete(keyId)
        assertNotNull(undo)
        // 假 DAO 不级联，手动模拟 Room 的 CASCADE 已把模型带走。
        modelDao.delete(modelId)
        assertEquals(null, modelDao.findById(modelId))

        assertTrue(undo!!.undo())
        val restoredModel = modelDao.findById(modelId)
        assertNotNull("模型应随密钥一起恢复", restoredModel)
        assertEquals(modelId, restoredModel!!.id)
        assertEquals("gpt-4o", restoredModel.modelId)
    }

    @Test
    fun `删除供应商后撤销，级联删掉的密钥与账号都按原 id 回来`() = runTest {
        val providerId = newProvider("合集")
        val keyId = keys.add(
            providerId = providerId,
            label = "KeyOne",
            note = "",
            secret = "tv-undo-secret-000003".toCharArray(),
            settings = settings(),
            balanceToken = null,
        )
        val accountId = accounts.add(
            providerId = providerId,
            label = "账号一",
            username = "user@example.test".toCharArray(),
            password = "pw-undo-000003".toCharArray(),
            loginUrl = null,
            loginMethods = setOf(com.lc33.tokenvault.domain.LoginMethod.GITHUB),
            note = null,
        )

        val undo = providers.delete(providerId)
        assertNotNull(undo)
        // 假 DAO 不级联，手动模拟 Room 的 CASCADE。
        keyDao.delete(keyId)
        accountDao.delete(accountId)
        assertEquals(null, keys.find(keyId))
        assertEquals(null, accountDao.findById(accountId))

        assertTrue(undo!!.undo())

        assertEquals(providerId, providers.find(providerId)?.id)
        assertEquals(keyId, keys.find(keyId)?.id)
        assertEquals(accountId, accountDao.findById(accountId)?.id)
        // 账号的用户名密文同样按原 id 写回，必须还能解开。
        assertEquals("user@example.test", accounts.revealUsername(accountId)?.concatToString())
    }

    @Test
    fun `撤销遇到唯一索引冲突时返回 false 而不是抛异常`() = runTest {
        val providerId = newProvider()
        val keyId = keys.add(
            providerId = providerId,
            label = "KeyOne",
            note = "",
            secret = "tv-undo-secret-000004".toCharArray(),
            settings = settings(),
            balanceToken = null,
        )
        val modelId = models.add(
            providerId = providerId,
            keyId = keyId,
            modelId = "gpt-4o",
            protocol = com.lc33.tokenvault.domain.Protocol.CHAT,
            needsReview = false,
        )
        val undo = models.delete(modelId)
        assertNotNull(undo)

        // 用户删除之后又建了同一个模型（同 providerId/keyId/modelId/protocol）。
        // 唯一索引会挡住恢复，撤销必须安静失败而不是崩。
        models.add(
            providerId = providerId,
            keyId = keyId,
            modelId = "gpt-4o",
            protocol = com.lc33.tokenvault.domain.Protocol.CHAT,
            needsReview = false,
        )

        assertFalse("唯一冲突时应安静失败", undo!!.undo())
    }

    @Test
    fun `批量删除的撤销会把整批供应商都恢复`() = runTest {
        val a = newProvider("甲")
        val b = newProvider("乙")
        val undoA = providers.delete(a)
        val undoB = providers.delete(b)
        assertNotNull(undoA)
        assertNotNull(undoB)

        val combined = listOf(undoA!!, undoB!!).combined()
        assertTrue(combined.undo())

        assertNotNull(providers.find(a))
        assertNotNull(providers.find(b))
    }

    @Test
    fun `撤销后的模型解密字段与连接配置完整`() = runTest {
        val providerId = newProvider()
        val token = "tv-balance-token-0005".toCharArray()
        val keyId = keys.add(
            providerId = providerId,
            label = "KeyOne",
            note = "备注",
            secret = "tv-undo-secret-000005".toCharArray(),
            settings = settings().copy(
                balanceUserId = "42",
                allowInsecure = true,
                timeoutSeconds = 35,
            ),
            balanceToken = token,
        )

        val undo = keys.delete(keyId)
        assertTrue(undo!!.undo())

        // 连接配置整行写回，不是只插了一行空壳。
        val restored = keys.find(keyId)
        assertEquals("42", restored?.settings?.balanceUserId)
        assertEquals(true, restored?.settings?.allowInsecure)
        assertEquals(35, restored?.settings?.timeoutSeconds)
        // 余额令牌密文按原 keyId 写回，仍可解密。
        assertEquals("tv-balance-token-0005", keys.revealBalanceToken(keyId)?.concatToString())
    }
}

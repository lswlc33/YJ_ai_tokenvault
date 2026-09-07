package com.lc33.tokenvault.engine

import com.lc33.tokenvault.backup.BackupCodec
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.dao.AppSettingDao
import com.lc33.tokenvault.data.repo.FakeApiKeyDao
import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.FakeClientProfileDao
import com.lc33.tokenvault.data.repo.FakeGroupDao
import com.lc33.tokenvault.data.repo.FakeModelDao
import com.lc33.tokenvault.data.repo.FakeProbeRunDao
import com.lc33.tokenvault.data.repo.FakeProviderAccountDao
import com.lc33.tokenvault.data.repo.FakeProviderDao
import com.lc33.tokenvault.data.repo.FieldCipher
import com.lc33.tokenvault.data.repo.ImmediateTransactions
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.platform.AutoLocker
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 备份导出 / 恢复编排（§12.1）测试（测试 11 的编排部分）。
 *
 * 用真 `VaultSession` + `SecretBox`，所以加密是端到端的：导出 reveal 明文、恢复用本机
 * DEK 重加密、自然键引用（分组名 / builtinKey）都能验。
 */
class BackupEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var bootStore: FileBootStore
    private lateinit var groupDao: FakeGroupDao
    private lateinit var providerDao: FakeProviderDao
    private lateinit var keyDao: FakeApiKeyDao
    private lateinit var accountDao: FakeProviderAccountDao
    private lateinit var profileDao: FakeClientProfileDao
    private lateinit var modelDao: FakeModelDao
    private lateinit var probeRunDao: FakeProbeRunDao
    private lateinit var settingDao: FakeAppSettingDao
    private lateinit var cipher: FieldCipher
    private lateinit var engine: BackupEngine

    private var now = 1_700_000_000_000L

    private fun slowClock(): () -> Long {
        var t = 0L
        return { t += 900_000_000L; t }
    }

    @Before
    fun setUp() {
        val file = File(temp.newFolder("files"), FileBootStore.FILE_NAME)
        bootStore = FileBootStore(file) { "test-device" }
        session = VaultSession(bootStore = bootStore, nowEpochMs = { now })
        session.onboard("194057".toCharArray(), slowClock())
        session.completeOnboarding()

        groupDao = FakeGroupDao()
        providerDao = FakeProviderDao()
        keyDao = FakeApiKeyDao()
        accountDao = FakeProviderAccountDao()
        profileDao = FakeClientProfileDao()
        modelDao = FakeModelDao()
        probeRunDao = FakeProbeRunDao()
        settingDao = FakeAppSettingDao()
        cipher = FieldCipher(session, SecretBox())

        engine = BackupEngine(
            store = com.lc33.tokenvault.data.repo.RoomBackupStore(
                groupDao = groupDao,
                providerDao = providerDao,
                keyDao = keyDao,
                accountDao = accountDao,
                profileDao = profileDao,
                modelDao = modelDao,
                probeRunDao = probeRunDao,
                appSettingDao = settingDao,
                cipher = cipher,
                transactions = ImmediateTransactions(),
                bootStore = bootStore,
                now = { now },
            ),
            codec = BackupCodec(SecureRandomBytes),
            random = SecureRandomBytes,
            audit = noopAudit(),
            autoLocker = AutoLocker(
                session = session,
                scope = CoroutineScope(Dispatchers.Unconfined),
                elapsedRealtimeMs = { 0L },
            ),
            now = { now },
        )
    }

    /** 日志埋点在测试里不关心内容，给一个空实现。 */
    private fun noopAudit(): com.lc33.tokenvault.domain.repo.AuditLogRepository =
        object : com.lc33.tokenvault.domain.repo.AuditLogRepository {
            override suspend fun record(
                level: com.lc33.tokenvault.domain.model.LogLevel,
                category: com.lc33.tokenvault.domain.model.LogCategory,
                message: String,
                detail: String?,
                providerId: Long?,
                keyId: Long?,
            ) = Unit

            override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.lc33.tokenvault.domain.model.AuditEntry>())

            override suspend fun clear() = Unit
        }

    /** 种一个带分组、密钥、账号、模型的供应商。返回 providerId。 */
    private suspend fun seedProvider(): Long {
        val groupId = groupDao.insert(GroupEntity(name = "工作", sortOrder = 0))
        val stamp = now
        val providerId = providerDao.insert(
            ProviderEntity(
                name = "Agent Router",
                apiBaseUrl = "https://r.example.com/v1",
                apiRoot = "https://r.example.com",
                apiVersion = "v1",
                groupId = groupId,
                balanceKind = "newapi",
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )
        val secretChars = "sk-secret-123".toCharArray()
        val secretBytes = secretChars.toUtf8Bytes()
        val fp = cipher.fingerprint(secretBytes)
        keyDao.insert(
            ApiKeyEntity(
                providerId = providerId,
                label = "默认",
                secretEnc = ByteArray(0),
                fingerprint = fp,
                createdAt = stamp,
                updatedAt = stamp,
            ),
            stamp,
        )
        keyDao.setSecret(1L, cipher.seal(secretBytes, FieldAad.of("api_keys", 1L, "secretEnc")), fp, stamp)
        secretBytes.zeroize()
        accountDao.insert(
            ProviderAccountEntity(
                providerId = providerId,
                label = "登录",
                usernameFp = null,
                loginUrl = null,
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )
        modelDao.insertIgnoring(
            ModelEntity(
                providerId = providerId,
                modelId = "claude-opus-5",
                protocol = "anthropic",
                firstSeenAt = stamp,
            ),
        )
        return providerId
    }

    @Test
    fun `导出后覆盖恢复到空库数据还原`() = runTest {
        val providerId = seedProvider()
        val password = "backup-password".toCharArray()

        val bytes = engine.export(password)

        // 清空，模拟新设备空库
        providerDao.clear()
        groupDao.clear()
        keyDao.clearForTest()
        accountDao.clearForTest()
        modelDao.clearForTest()

        val result = engine.restore(bytes, password, RestoreMode.OVERWRITE)
        assertEquals(1, result.importedProviders)

        // 供应商还原
        val provider = providerDao.rows.single()
        assertEquals("Agent Router", provider.name)
        assertEquals("https://r.example.com", provider.apiRoot)

        // 分组自然键还原（name 匹配，不是搬 id）
        val group = groupDao.rows.single()
        assertEquals("工作", group.name)

        // 密钥还原且可解回明文（本机 DEK 重加密）
        val key = keyDao.rows.single()
        assertEquals("默认", key.label)
        assertTrue(key.isDefault)
        val revealed = session.withFieldKey { k ->
            SecretBox().open(key.secretEnc, k, FieldAad.of("api_keys", key.id, "secretEnc"), "t")
        }
        assertEquals("sk-secret-123", String(revealed, Charsets.UTF_8))

        // 模型还原
        val model = modelDao.rows.single()
        assertEquals("claude-opus-5", model.modelId)
        assertEquals("anthropic", model.protocol)
    }

    @Test
    fun `合并模式不重复导入已存在的供应商`() = runTest {
        seedProvider()
        val password = "pw".toCharArray()
        val bytes = engine.export(password)

        // 不清理，直接合并恢复同一份备份
        val result = engine.restore(bytes, password, RestoreMode.MERGE)
        assertEquals(0, result.importedProviders)
        assertEquals(1, providerDao.rows.size)
        assertEquals(1, keyDao.rows.size)
    }

    @Test
    fun `覆盖模式清掉原有数据`() = runTest {
        // 先种一个 A
        seedProvider()
        // 导出后再种一个 B（不同名字），然后覆盖恢复 A 的备份
        val password = "pw".toCharArray()
        val bytesA = engine.export(password)

        // 种 B
        providerDao.insert(
            ProviderEntity(
                name = "另一个",
                apiBaseUrl = "https://other.example.com/v1",
                apiRoot = "https://other.example.com",
                createdAt = now,
                updatedAt = now,
            ),
        )

        engine.restore(bytesA, password, RestoreMode.OVERWRITE)
        // 只剩 A
        assertEquals(listOf("Agent Router"), providerDao.rows.map { it.name })
    }

    @Test
    fun `恢复后探测结果被清空`() = runTest {
        seedProvider()
        val password = "pw".toCharArray()
        val bytes = engine.export(password)

        // 模拟探测结果：写一个 probe run
        probeRunDao.insert(
            com.lc33.tokenvault.data.entity.ProbeRunEntity(
                scope = "all",
                startedAt = now,
            ),
        )

        engine.restore(bytes, password, RestoreMode.OVERWRITE)
        // 覆盖恢复后 probe_runs 被清空（红线 28）
        assertEquals(0, probeRunDao.rows.size)
    }

    @Test
    fun `口令错恢复抛损坏异常`() = runTest {
        seedProvider()
        val bytes = engine.export("right".toCharArray())
        val e = runCatching { engine.restore(bytes, "wrong".toCharArray(), RestoreMode.MERGE) }
            .exceptionOrNull()
        assertTrue(e is com.lc33.tokenvault.backup.BackupCorruptException)
    }
}

// 帮助函数：绕过 internal 的 toUtf8（测试里直接编码）
private fun CharArray.toUtf8Bytes(): ByteArray = String(this).toByteArray(Charsets.UTF_8)

package com.lc33.tokenvault.engine

import com.lc33.tokenvault.backup.BackupCodec
import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecureRandomBytes
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.crypto.zeroize
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.GroupEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderAccountEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.data.repo.FakeApiKeyDao
import com.lc33.tokenvault.data.repo.FakeAppSettingDao
import com.lc33.tokenvault.data.repo.FakeClientProfileDao
import com.lc33.tokenvault.data.repo.FakeGroupDao
import com.lc33.tokenvault.data.repo.FakeKeySettingsDao
import com.lc33.tokenvault.data.repo.FakeModelDao
import com.lc33.tokenvault.data.repo.FakeProbeRunDao
import com.lc33.tokenvault.data.repo.FakeProviderAccountDao
import com.lc33.tokenvault.data.repo.FakeProviderDao
import com.lc33.tokenvault.data.repo.FieldCipher
import com.lc33.tokenvault.data.repo.ImmediateTransactions
import com.lc33.tokenvault.data.repo.RoomBackupStore
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

class BackupEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var bootStore: FileBootStore
    private lateinit var groupDao: FakeGroupDao
    private lateinit var providerDao: FakeProviderDao
    private lateinit var keyDao: FakeApiKeyDao
    private lateinit var settingsDao: FakeKeySettingsDao
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
        settingsDao = FakeKeySettingsDao()
        keyDao = FakeApiKeyDao(settingsDao)
        accountDao = FakeProviderAccountDao()
        profileDao = FakeClientProfileDao()
        modelDao = FakeModelDao()
        probeRunDao = FakeProbeRunDao()
        settingDao = FakeAppSettingDao()
        cipher = FieldCipher(session, SecretBox())

        engine = BackupEngine(
            store = RoomBackupStore(
                groupDao = groupDao,
                providerDao = providerDao,
                keyDao = keyDao,
                settingsDao = settingsDao,
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

            override fun observeRecent(
                limit: Int,
                minLevel: com.lc33.tokenvault.domain.model.LogLevel,
            ) = kotlinx.coroutines.flow.flowOf(emptyList<com.lc33.tokenvault.domain.model.AuditEntry>())

            override suspend fun trimOlderThan(before: Long) = Unit

            override suspend fun clear() = Unit
        }

    private suspend fun seedProvider(): Long {
        val groupId = groupDao.insert(GroupEntity(name = "工作", sortOrder = 0))
        val stamp = now
        val providerId = providerDao.insert(
            ProviderEntity(
                name = "Agent Router",
                websiteUrl = "https://r.example.com",
                groupId = groupId,
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )

        val secretChars = "sk-secret-123".toCharArray()
        val secretBytes = String(secretChars).toByteArray(Charsets.UTF_8)
        val fingerprint = cipher.fingerprint(secretBytes)
        val keyId = keyDao.insertRaw(
            ApiKeyEntity(
                providerId = providerId,
                label = "主力",
                note = "月度限额较高",
                secretEnc = ByteArray(0),
                fingerprint = fingerprint,
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )
        keyDao.setSecret(
            keyId,
            cipher.seal(secretBytes, FieldAad.of("api_keys", keyId, "secretEnc")),
            fingerprint,
            stamp,
        )
        secretBytes.zeroize()
        settingsDao.insert(
            KeySettingsEntity(
                keyId = keyId,
                apiBaseUrl = "https://r.example.com/v1",
                apiRoot = "https://r.example.com",
                supportedProtocols = "chat,anthropic",
                balanceKind = "newapi",
                balanceUserId = "199628",
                updatedAt = stamp,
            ),
        )
        accountDao.insert(
            ProviderAccountEntity(
                providerId = providerId,
                label = "登录",
                createdAt = stamp,
                updatedAt = stamp,
            ),
        )
        modelDao.insertIgnoring(
            ModelEntity(
                providerId = providerId,
                keyId = keyId,
                modelId = "claude-opus-5",
                protocol = "anthropic",
                firstSeenAt = stamp,
            ),
        )
        return providerId
    }

    @Test
    fun `导出后覆盖恢复到空库数据还原`() = runTest {
        seedProvider()
        val password = "backup-password".toCharArray()
        val bytes = engine.export(password)

        providerDao.clear()
        groupDao.clear()
        keyDao.clearForTest()
        accountDao.clearForTest()
        modelDao.clearForTest()

        val result = engine.restore(bytes, password, RestoreMode.OVERWRITE)
        assertEquals(1, result.importedProviders)

        val provider = providerDao.rows.single()
        assertEquals("Agent Router", provider.name)
        assertEquals("https://r.example.com", provider.websiteUrl)
        assertEquals("工作", groupDao.rows.single().name)

        val key = keyDao.rows.single()
        assertEquals("主力", key.label)
        assertEquals("月度限额较高", key.note)
        val revealed = session.withFieldKey { k ->
            SecretBox().open(key.secretEnc, k, FieldAad.of("api_keys", key.id, "secretEnc"), "t")
        }
        assertEquals("sk-secret-123", String(revealed, Charsets.UTF_8))

        val settings = settingsDao.rows.single()
        assertEquals("https://r.example.com/v1", settings.apiBaseUrl)
        assertEquals("newapi", settings.balanceKind)
        assertEquals("199628", settings.balanceUserId)

        val model = modelDao.rows.single()
        assertEquals("claude-opus-5", model.modelId)
        assertEquals("anthropic", model.protocol)
        assertEquals(key.id, model.keyId)
    }

    @Test
    fun `合并模式不重复导入已存在的供应商`() = runTest {
        seedProvider()
        val password = "pw".toCharArray()
        val bytes = engine.export(password)

        val result = engine.restore(bytes, password, RestoreMode.MERGE)
        assertEquals(0, result.importedProviders)
        assertEquals(1, providerDao.rows.size)
        assertEquals(1, keyDao.rows.size)
    }

    @Test
    fun `覆盖模式清掉原有数据`() = runTest {
        seedProvider()
        val password = "pw".toCharArray()
        val bytesA = engine.export(password)

        providerDao.insert(
            ProviderEntity(
                name = "另一个",
                websiteUrl = "https://other.example.com",
                createdAt = now,
                updatedAt = now,
            ),
        )

        engine.restore(bytesA, password, RestoreMode.OVERWRITE)
        assertEquals(listOf("Agent Router"), providerDao.rows.map { it.name })
    }

    @Test
    fun `恢复后探测结果被清空`() = runTest {
        seedProvider()
        val password = "pw".toCharArray()
        val bytes = engine.export(password)

        probeRunDao.insert(
            com.lc33.tokenvault.data.entity.ProbeRunEntity(
                scope = "all",
                startedAt = now,
            ),
        )

        engine.restore(bytes, password, RestoreMode.OVERWRITE)
        assertEquals(0, probeRunDao.rows.size)
    }

    @Test
    fun `口令错恢复抛损坏异常`() = runTest {
        seedProvider()
        val bytes = engine.export("right".toCharArray())
        val error = runCatching { engine.restore(bytes, "wrong".toCharArray(), RestoreMode.MERGE) }
            .exceptionOrNull()
        assertTrue(error is com.lc33.tokenvault.backup.BackupCorruptException)
    }
}

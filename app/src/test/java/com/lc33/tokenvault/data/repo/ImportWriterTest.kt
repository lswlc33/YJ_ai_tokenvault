package com.lc33.tokenvault.data.repo

import com.lc33.tokenvault.domain.repo.ImportWriter

import com.lc33.tokenvault.domain.repo.TransactionRunner

import com.lc33.tokenvault.crypto.FieldAad
import com.lc33.tokenvault.crypto.SecretBox
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.importer.ParsedAccount
import com.lc33.tokenvault.importer.ParsedKey
import com.lc33.tokenvault.importer.ParsedModel
import com.lc33.tokenvault.importer.ParsedRecord
import com.lc33.tokenvault.importer.TextImporter
import com.lc33.tokenvault.platform.FileBootStore
import com.lc33.tokenvault.platform.VaultSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 文本导入的落库编排。
 *
 * 验证的是"解析结果 → 数据库"这条链路的正确性：供应商先写、密钥第一张默认、
 * 账号加密、模型带 needsReview。用真 `VaultSession` + `SecretBox`，所以加密是端到端的。
 */
class ImportWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var session: VaultSession
    private lateinit var providerDao: FakeProviderDao
    private lateinit var keyDao: FakeApiKeyDao
    private lateinit var accountDao: FakeProviderAccountDao
    private lateinit var modelDao: FakeModelDao
    private lateinit var writer: ImportWriter

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

        providerDao = FakeProviderDao()
        keyDao = FakeApiKeyDao()
        accountDao = FakeProviderAccountDao()
        modelDao = FakeModelDao()
        val cipher = FieldCipher(session, SecretBox())
        writer = ImportWriter(
            providers = RoomProviderRepository(providerDao, cipher, ImmediateTransactions()) { now },
            keys = RoomApiKeyRepository(keyDao, cipher, ImmediateTransactions()) { now },
            accounts = RoomProviderAccountRepository(accountDao, cipher, ImmediateTransactions()) { now },
            models = RoomModelRepository(modelDao, ImmediateTransactions()) { now },
            transactions = ImmediateTransactions(),
        )
    }

    private fun sample(): List<ParsedRecord> {
        val text = """
            供应商名称 Agent Router
            备注 公司专用账号
            官网链接 https://ps.air-outer.com/
            API Key sk-TEST0000000000000000000000000000000000000000000000000001
            API请求地址 https://ps.air-outer.com/v1

            支持端点类型

            - Chat Completions
            - Responses (原生)

            模型列表
            gpt-5.6-sol Responses
            claude-opus-5 Anthropic

            余额查询类型 NewAPI
            请求地址 https://ps.air-outer.com 不填默认是端点域名
            访问令牌(在个人安全设置里获取)
            TESTtoken0000000000000000000000000001=
            用户ID 199628
        """.trimIndent()
        return TextImporter.parse(text).records
    }

    @Test
    fun `一条记录落库全链路正确`() = runTest {
        val count = writer.write(sample())
        assertEquals(1, count)

        // 供应商
        val provider = providerDao.rows.single()
        assertEquals("Agent Router", provider.name)
        assertEquals("https://ps.air-outer.com", provider.apiRoot)
        assertEquals("v1", provider.apiVersion)
        assertEquals(BalanceKind.NEWAPI.wireName, provider.balanceKind)
        assertEquals("199628", provider.balanceUserId)

        // 密钥：一张，且是默认
        val key = keyDao.rows.single()
        assertEquals(provider.id, key.providerId)
        assertTrue(key.isDefault)
        assertEquals("主号", key.label)

        // 模型：两个，协议正确
        assertEquals(2, modelDao.rows.size)
        val byId = modelDao.rows.associateBy { it.modelId }
        assertEquals(Protocol.RESPONSES.wireName, byId["gpt-5.6-sol"]?.protocol)
        assertEquals(Protocol.ANTHROPIC.wireName, byId["claude-opus-5"]?.protocol)
    }

    @Test
    fun `多账号分组落库`() = runTest {
        val text = """
            供应商名称 多账号
            API请求地址 https://api.example.com/v1
            API Key sk-TEST0000000000000000000000000000000000000000000000000004
            余额查询类型 NewAPI
            访问令牌 TESTtoken0000000000000000000000000004==
            用户ID 10001

            平台账号 company@example.com
            平台密码 Password1
            账号备注 公司主号

            平台账号 13800138000
            平台密码 Password2
            账号备注 备用号
        """.trimIndent()
        writer.write(TextImporter.parse(text).records)

        assertEquals(2, accountDao.rows.size)
        val providerId = providerDao.rows.single().id
        val labels = accountDao.rows.map { it.label }.toSet()
        assertEquals(setOf("公司主号", "备用号"), labels)

        // 用户名密码加密且可解回（用第一张账号验证）
        val first = accountDao.rows.first()
        val usernamePlain = session.withFieldKey { key ->
            SecretBox().open(first.usernameEnc!!, key, FieldAad.of("provider_accounts", first.id, "usernameEnc"), "t")
        }
        assertEquals("company@example.com", String(usernamePlain, Charsets.UTF_8))
        assertEquals(providerId, first.providerId)
    }

    @Test
    fun `模型 needsReview 落库`() = runTest {
        val text = """
            供应商名称 DeepSeek
            API请求地址 https://api.deepseek.com/v1
            API Key sk-TEST0000000000000000000000000000000000000000000000000003
            模型列表
            DeepSeek V4 Pro Responses
        """.trimIndent()
        writer.write(TextImporter.parse(text).records)

        val model = modelDao.rows.single()
        assertEquals("DeepSeek V4 Pro", model.modelId)
        assertTrue(model.needsReview)
    }
}

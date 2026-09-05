package com.lc33.tokenvault.data.mapper

import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实体 ↔ 领域模型的往返（§14.3 测试 2 的映射那一半）。
 *
 * 这一层的错误**不报错**：字段接错、枚举名换了、密文被拷成引用相同但内容不同的数组，
 * 全都编译通过。表现是"某个供应商的协议少了一个""改完设置一保存探测开关全变默认值"。
 * 所以每个类型都往返一次，并且逐字段比。
 */
class EntityMappersTest {

    private val fullProvider = ProviderEntity(
        id = 7,
        name = "Agent Router",
        note = "备注",
        websiteUrl = "https://example.test",
        apiBaseUrl = "https://api.example.test/v1/",
        apiRoot = "https://api.example.test",
        apiVersion = "v1",
        supportedProtocols = "chat,responses,anthropic",
        pathOverrides = """{"anthropic":"/anthropic/v1/messages"}""",
        authStyle = "x_api_key",
        allowInsecure = true,
        clientProfileId = 3,
        groupId = 2,
        color = 4,
        pinned = true,
        sortOrder = 9,
        balanceKind = "newapi",
        balanceBaseUrl = "https://api.example.test",
        balanceUserId = "42",
        balanceTokenEnc = byteArrayOf(1, 2, 3, 4),
        balanceConfig = """{"valuePath":"data.quota"}""",
        quotaPerUnit = 500_000.0,
        balanceAmount = 12.5,
        balanceUsed = 3.25,
        balanceCurrency = "USD",
        balanceRaw = """{"quota":6250000}""",
        balanceCheckedAt = 1_700_000_000_000,
        balanceError = null,
        quotaCalibrated = true,
        timeoutSeconds = 45,
        probeEnabled = false,
        probeReachability = false,
        probeKeyValidity = false,
        probeBalance = false,
        probeModels = true,
        createdAt = 1_600_000_000_000,
        updatedAt = 1_700_000_000_001,
    )

    @Test
    fun `供应商往返之后每个字段都还在`() {
        val back = fullProvider.toDomain().toEntity()

        // ProviderEntity.equals 只比五个字段（它是给 Room 用的），所以这里逐字段比，
        // 否则漏接一个字段测试照样绿
        assertEquals(fullProvider.copy(balanceTokenEnc = null), back.copy(balanceTokenEnc = null))
        assertArrayEquals(fullProvider.balanceTokenEnc, back.balanceTokenEnc)
    }

    @Test
    fun `协议 CSV 的顺序不变`() {
        // 顺序变了不会报错，表现是那一排 chips 每次刷新都在跳
        val domain = fullProvider.toDomain()
        assertEquals(listOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC), domain.supportedProtocols.toList())
        assertEquals("chat,responses,anthropic", domain.toEntity().supportedProtocols)
    }

    @Test
    fun `路径覆盖与探测开关都往返`() {
        val domain = fullProvider.toDomain()
        assertEquals(mapOf(Protocol.ANTHROPIC to "/anthropic/v1/messages"), domain.pathOverrides)
        // 五个开关刻意与默认值全部相反：接错某一个也能被这一条抓到
        assertEquals(false, domain.probe.enabled)
        assertEquals(false, domain.probe.reachability)
        assertEquals(false, domain.probe.keyValidity)
        assertEquals(false, domain.probe.balance)
        assertEquals(true, domain.probe.models)
    }

    @Test
    fun `没查过余额的行读出来没有快照`() {
        val fresh = fullProvider.copy(
            balanceAmount = null,
            balanceUsed = null,
            balanceCurrency = null,
            balanceRaw = null,
            balanceCheckedAt = null,
            balanceError = null,
        )
        // "没配置/没查过"与"查过一次"在 UI 上不一样，所以不能给一个 amount=null 的空快照
        assertNull(fresh.toDomain().balance)
    }

    @Test
    fun `查询失败与余额为零是两回事`() {
        val failed = fullProvider.copy(balanceAmount = null, balanceError = "timeout").toDomain().balance
        assertNotNull(failed)
        assertTrue(failed!!.failed)

        val zero = fullProvider.copy(balanceAmount = 0.0, balanceError = null).toDomain().balance
        // 红线 14 的邻居（§9.3）：amount = 0 必须表示"真的没钱了"，不许兼表失败
        assertEquals(0.0, zero!!.amount!!, 0.0)
        assertTrue(!zero.failed)
    }

    @Test
    fun `认不出来的鉴权风格与余额种类退回默认档而不是抛`() {
        val weird = fullProvider.copy(authStyle = "hmac-v9", balanceKind = "someFutureVendor").toDomain()
        assertEquals(AuthStyle.AUTO, weird.authStyle)
        assertEquals(BalanceKind.NONE, weird.balanceKind)
    }

    @Test
    fun `密钥往返，两个状态列各归各位`() {
        val entity = ApiKeyEntity(
            id = 11,
            providerId = 7,
            label = "主力",
            secretEnc = byteArrayOf(9, 8, 7),
            fingerprint = "a".repeat(32),
            isDefault = true,
            enabled = false,
            health = "client_blocked",
            lastOutcome = "rate_limited",
            healthDetail = "unauthorized client detected",
            httpStatus = 401,
            latencyMs = 321,
            checkedAt = 1_700_000_000_000,
            okAt = 1_699_000_000_000,
            sortOrder = 2,
            createdAt = 1_600_000_000_000,
            updatedAt = 1_700_000_000_001,
        )
        val domain = entity.toDomain()
        // 红线 11：持久结论与本轮发生了什么是两列，映射不许把它们串在一起
        assertEquals(KeyHealth.CLIENT_BLOCKED, domain.health)
        assertEquals(ProbeOutcome.RATE_LIMITED, domain.lastOutcome)
        assertTrue(!domain.lastOutcome.rewritesHealth)

        val back = domain.toEntity()
        assertEquals(entity.copy(secretEnc = ByteArray(0)), back.copy(secretEnc = ByteArray(0)))
        // 密文必须按内容一致：拷成另一个数组也能编译通过，而那意味着这一行永远解不开
        assertArrayEquals(entity.secretEnc, back.secretEnc)
    }

    @Test
    fun `认不出来的健康档退回 UNKNOWN，不是 OK`() {
        val entity = ApiKeyEntity(
            providerId = 1,
            secretEnc = ByteArray(1),
            fingerprint = "f",
            health = "quantum_ok",
            createdAt = 0,
            updatedAt = 0,
        )
        // 退到 OK 会让一张状态未知的密钥在首页显示成绿的
        assertEquals(KeyHealth.UNKNOWN, entity.toDomain().health)
    }

    @Test
    fun `模型往返，协议认不出来时给 CHAT 而不是丢掉这一行`() {
        val entity = ModelEntity(
            id = 5,
            providerId = 7,
            modelId = "claude-opus-5",
            protocol = "anthropic",
            displayName = "Claude Opus 5",
            source = "discovered",
            discoveredVia = "anthropic",
            enabled = false,
            favorite = true,
            needsReview = true,
            catalogKey = "anthropic/claude-opus-5",
            probeState = "not_found",
            lastOutcome = "conclusive_fail",
            probeDetail = "model not found",
            latencyMs = 12,
            probedAt = 1_700_000_000_000,
            firstSeenAt = 1_600_000_000_000,
            lastSeenAt = 1_700_000_000_000,
            sortOrder = 3,
        )
        assertEquals(entity, entity.toDomain().toEntity())

        val unknown = entity.copy(protocol = "grpc-future").toDomain()
        assertEquals(Protocol.CHAT, unknown.protocol)
    }

    @Test
    fun `客户端预设往返之后请求头顺序不变`() {
        val entity = ClientProfileEntity(
            id = 2,
            name = "claude_code",
            builtinKey = "claude_code",
            userAgent = "claude-cli/1.0",
            headers = """[["x-app","cli"],["anthropic-version","2023-06-01"]]""",
            bodyPatch = """{"stream":false}""",
            protocols = "chat,anthropic",
            verified = true,
            builtinRev = 3,
            userEdited = true,
            sortOrder = 1,
        )
        val domain = entity.toDomain()
        // 部分上游看请求头顺序（§6.2），所以顺序是数据的一部分
        assertEquals(listOf("x-app" to "cli", "anthropic-version" to "2023-06-01"), domain.headers)
        assertEquals(entity, domain.toEntity())
    }

    @Test
    fun `未知币种在快照里是 UNKNOWN 而不是空串`() {
        val snapshot = fullProvider.copy(balanceCurrency = null, balanceAmount = 1.0).toDomain().balance
        // 红线 15：金额必须带币种。空串会被格式化成一个没有单位的数字
        assertEquals(BalanceSnapshot.UNKNOWN_CURRENCY, snapshot!!.currency)
    }
}

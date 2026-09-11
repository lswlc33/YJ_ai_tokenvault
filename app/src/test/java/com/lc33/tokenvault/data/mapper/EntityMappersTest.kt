package com.lc33.tokenvault.data.mapper

import com.lc33.tokenvault.data.dao.ApiKeyWithSettingsRow
import com.lc33.tokenvault.data.entity.ApiKeyEntity
import com.lc33.tokenvault.data.entity.ClientProfileEntity
import com.lc33.tokenvault.data.entity.KeySettingsEntity
import com.lc33.tokenvault.data.entity.ModelEntity
import com.lc33.tokenvault.data.entity.ProviderEntity
import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ProbeOutcome
import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.KeyProbeSettings
import com.lc33.tokenvault.domain.model.KeySettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityMappersTest {

    @Test
    fun `供应商合集往返之后组织字段都还在`() {
        val entity = ProviderEntity(
            id = 7,
            name = "Agent Router",
            note = "备注",
            websiteUrl = "https://example.test",
            websiteLatencyMs = 123,
            websiteCheckedAt = 1_700_000_000_000,
            websiteError = null,
            groupId = 2,
            color = 4,
            pinned = true,
            sortOrder = 9,
            createdAt = 1_600_000_000_000,
            updatedAt = 1_700_000_000_001,
        )
        assertEquals(entity, entity.toDomain().toEntity())
    }

    @Test
    fun `Key 行为配置往返之后字段与顺序都保留`() {
        val entity = KeySettingsEntity(
            keyId = 11,
            apiBaseUrl = "https://api.example.test/v1/",
            apiRoot = "https://api.example.test",
            apiVersion = "v1",
            supportedProtocols = "chat,responses,anthropic",
            pathOverrides = """{"anthropic":"/anthropic/v1/messages"}""",
            authStyle = "x_api_key",
            allowInsecure = true,
            clientProfileId = 3,
            timeoutSeconds = 45,
            balanceKind = "newapi",
            balanceBaseUrl = "https://api.example.test",
            balanceUserId = "42",
            balanceTokenEnc = byteArrayOf(1, 2, 3),
            balanceConfig = """{"valuePath":"data.quota"}""",
            quotaPerUnit = 500_000.0,
            quotaCalibrated = true,
            probeEnabled = false,
            probeReachability = false,
            probeKeyValidity = false,
            probeBalance = false,
            probeModels = true,
            probeModelReachability = true,
            updatedAt = 1_700_000_000_001,
        )
        val domain = entity.toDomain()
        assertEquals(listOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC), domain.supportedProtocols.toList())
        assertEquals(mapOf(Protocol.ANTHROPIC to "/anthropic/v1/messages"), domain.pathOverrides)
        assertEquals(AuthStyle.X_API_KEY, domain.authStyle)
        assertEquals(BalanceKind.NEWAPI, domain.balanceKind)
        assertEquals(
            KeyProbeSettings(false, false, false, false, true, true),
            domain.probe,
        )
        val back = domain.toEntity(11, 1_700_000_000_001)
        assertEquals(entity.copy(balanceTokenEnc = null), back.copy(balanceTokenEnc = null))
        assertArrayEquals(entity.balanceTokenEnc, back.balanceTokenEnc)
    }

    @Test
    fun `密钥行往返，备注与状态列各归各位`() {
        val key = ApiKeyEntity(
            id = 11,
            providerId = 7,
            label = "主力",
            note = "月度限额较高",
            secretEnc = byteArrayOf(9, 8, 7),
            fingerprint = "a".repeat(32),
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
        val settings = KeySettings(apiBaseUrl = "https://x.test", apiRoot = "https://x.test")
        val row = ApiKeyWithSettingsRow(key, settings.toEntity(key.id, key.updatedAt))
        val domain = row.toDomain()

        assertEquals(KeyHealth.CLIENT_BLOCKED, domain.health)
        assertEquals(ProbeOutcome.RATE_LIMITED, domain.lastOutcome)
        assertEquals("月度限额较高", domain.note)
        assertEquals(settings, domain.settings)

        val back = domain.toEntity()
        assertEquals(key, back)
    }

    @Test
    fun `认不出来的健康档退回 UNKNOWN，不是 OK`() {
        val key = ApiKeyEntity(
            providerId = 1,
            secretEnc = ByteArray(1),
            fingerprint = "f",
            health = "quantum_ok",
            createdAt = 0,
            updatedAt = 0,
        )
        val row = ApiKeyWithSettingsRow(
            key,
            KeySettingsEntity(keyId = key.id, apiBaseUrl = "", apiRoot = "", updatedAt = 0),
        )
        assertEquals(KeyHealth.UNKNOWN, row.toDomain().health)
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
        assertEquals(Protocol.CHAT, entity.copy(protocol = "grpc-future").toDomain().protocol)
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
        assertEquals(listOf("x-app" to "cli", "anthropic-version" to "2023-06-01"), domain.headers)
        assertEquals(entity, domain.toEntity())
        assertTrue(domain.protocols.containsAll(setOf(Protocol.CHAT, Protocol.ANTHROPIC)))
    }
}

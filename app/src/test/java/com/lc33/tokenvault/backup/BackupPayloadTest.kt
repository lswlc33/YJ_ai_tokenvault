package com.lc33.tokenvault.backup

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackupPayload 序列化与 gzip（§12.1）纯函数测试。
 *
 * 覆盖：JSON 往返（自然键、明文密钥不丢）、gzip/gunzip 往返、损坏 gzip 抛异常（红线 8）。
 */
class BackupPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `payload JSON 往返不丢字段`() {
        val payload = BackupPayload(
            groups = listOf(BackupGroup(name = "工作", sortOrder = 1)),
            providers = listOf(
                BackupProvider(
                    name = "Agent Router",
                    apiBaseUrl = "https://r.example.com/v1",
                    apiRoot = "https://r.example.com",
                    supportedProtocols = listOf("chat", "responses", "anthropic"),
                    authStyle = "x_api_key",
                    clientProfileKey = "claude_code",
                    groupName = "工作",
                    balanceKind = "newapi",
                    balanceToken = "sk-token-plaintext",
                ),
            ),
            apiKeys = listOf(
                BackupApiKey(
                    providerName = "Agent Router",
                    providerApiRoot = "https://r.example.com",
                    label = "默认",
                    secret = "sk-abc123",
                    isDefault = true,
                ),
            ),
            providerAccounts = listOf(
                BackupAccount(
                    providerName = "Agent Router",
                    providerApiRoot = "https://r.example.com",
                    label = "登录",
                    username = "me@example.com",
                    password = "hunter2",
                ),
            ),
            models = listOf(
                BackupModel(
                    providerName = "Agent Router",
                    providerApiRoot = "https://r.example.com",
                    modelId = "claude-opus-5",
                    protocol = "anthropic",
                    source = "discovered",
                ),
            ),
            clientProfiles = listOf(
                BackupProfile(
                    name = "我的抓包",
                    userAgent = "custom/1.0",
                    headers = listOf(listOf("x-app", "cli"), listOf("x-ver", "2")),
                    userEdited = true,
                ),
            ),
            appSettings = listOf(BackupSetting(key = "themeMode", value = "dark")),
        )

        val encoded = json.encodeToString(BackupPayload.serializer(), payload)
        val decoded = json.decodeFromString(BackupPayload.serializer(), encoded)

        assertEquals(payload.groups, decoded.groups)
        assertEquals(payload.providers, decoded.providers)
        assertEquals(payload.apiKeys, decoded.apiKeys)
        assertEquals(payload.providerAccounts, decoded.providerAccounts)
        assertEquals(payload.models, decoded.models)
        assertEquals(payload.clientProfiles, decoded.clientProfiles)
        assertEquals(payload.appSettings, decoded.appSettings)
    }

    @Test
    fun `明文密钥与密码在 JSON 里是可见的`() {
        // 红线：payload 里密钥是明文（整包已加密），恢复端重新加密。
        val payload = BackupPayload(
            apiKeys = listOf(
                BackupApiKey(
                    providerName = "p", providerApiRoot = "r", secret = "sk-secret",
                ),
            ),
        )
        val encoded = json.encodeToString(BackupPayload.serializer(), payload)
        assertTrue(encoded.contains("sk-secret"))
    }

    @Test
    fun `gzip 往返一致`() {
        val bytes = """{"providers":[]}""".encodeToByteArray()
        val compressed = gzip(bytes)
        val restored = gunzip(compressed)
        assertTrue(restored.contentEquals(bytes))
    }

    @Test
    fun `gzip 确实压缩了重复内容`() {
        val big = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa".encodeToByteArray()
        val compressed = gzip(big)
        assertTrue(compressed.size < big.size)
    }

    @Test
    fun `损坏 gzip 抛损坏异常不返回 null`() {
        val bad = ByteArray(10) { 0x00 }
        val e = runCatching { gunzip(bad) }.exceptionOrNull()
        assertTrue(e is BackupCorruptException)
    }
}

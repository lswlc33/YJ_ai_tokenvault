package com.lc33.tokenvault.backup

import com.lc33.tokenvault.crypto.CryptoProvider
import com.lc33.tokenvault.crypto.KdfParams
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨平台互操作：下面的夹具是 **JVM / Android 侧**（cryptography-kotlin 的 JDK provider）
 * 做出来的真实备份包，断言它在**任何**平台都解得开。
 *
 * 为什么必须有这一条：备份包是全项目唯一一处"A 端加密、B 端解密"的数据——字段级的
 * `SecretBox` 只在本机读写，结构上碰不到跨平台。JDK 侧 AES-GCM 输出 `密文‖tag`，iOS 侧走
 * CryptoKit provider（0.5.0 里它把 CryptoKit `combined` 的前 12 字节 nonce 切掉、末 16 字节
 * 当 tag），两边恰好一致。但这份一致此前没有任何测试守着：[BackupCodecTest] 只在本平台
 * 往返，"安卓做的包在 iOS 上解不开"它发现不了。挂在 commonTest 上，JVM 与 CI 的
 * `:shared:iosSimulatorArm64Test` 都会跑——只有后者才算真跨平台。
 *
 * 反方向（iOS 导出 → 安卓恢复）没有第二份夹具：GCM 的密文与 tag 由
 * `(key, nonce, plaintext, aad)` 唯一决定，两端差别只在拼装顺序，而拼装顺序正是这一条在验的东西。
 *
 * KDF 取 [KdfParams.MIN_ITERATIONS]：`iter` 是明文 header 的一部分，两端照着算即可，
 * 取最小档是为了这一条在模拟器上不拖慢 CI。
 */
class BackupCodecInteropTest {

    @OptIn(ExperimentalEncodingApi::class)
    private val fixture: ByteArray = Base64.decode(FIXTURE_B64.joinToString(""))

    @Test
    fun `安卓侧产出的备份包在本平台解得开`() {
        val decoded = BackupCodec().decode(fixture, PASSPHRASE.toCharArray())

        assertEquals("android-fixture", decoded.header.deviceId)
        assertEquals(42L, decoded.header.revision)
        assertEquals(BackupHeader.CIPHER_AES_256_GCM, decoded.header.cipher)
        assertEquals(KdfParams.MIN_ITERATIONS, decoded.header.kdf.iterations)
        assertEquals(BackupItemCounts(providers = 1, keys = 1), decoded.header.itemCounts)

        val payload = json.decodeFromString(
            BackupPayload.serializer(),
            gunzip(decoded.payload).decodeToString(),
        )
        // 断言到明文里的值，而不只是"没抛异常"：换向 / 错位这类实现差异也能过
        // "解出点东西"这一关，只有内容能把"解开了"和"解出来是另一套字节"分开。
        assertEquals(1, payload.groups.size)
        assertEquals(1, payload.providers.size)
        assertEquals("OpenAI", payload.providers.single().name)
        assertEquals("https://api.openai.com", payload.providers.single().apiRoot)
        assertEquals(1, payload.apiKeys.size)
        assertEquals(KEY_SECRET, payload.apiKeys.single().secret)
        assertTrue(payload.apiKeys.single().isDefault)
        // 把本平台真正用到的 provider 打出来：CI 的 ios 日志里出现
        // `iOS(CryptoKit+Apple)` 才算这条夹具在苹果的原语上跑过，而不是只在 JVM 上绿。
        println("interop fixture decoded on provider=" + CryptoProvider.provider.name)
    }

    @Test
    fun `口令不对时这份包仍然挡得住`() {
        val error = runCatching { BackupCodec().decode(fixture, "654321".toCharArray()) }.exceptionOrNull()
        assertTrue(error is BackupCorruptException)
    }

    private companion object {
        const val PASSPHRASE = "123456"

        const val KEY_SECRET = "fixture-key-not-real-0123456789"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 由 JDK 侧 `BackupCodec.encode` 产出后 base64 固化（口令见 [PASSPHRASE]）。 */
        val FIXTURE_B64 = listOf(
            "WUpWQVVMVDEAAAEheyJmb3JtYXQiOjEsInNjaGVtYSI6MSwiY3JlYXRlZEF0IjoxNzU3MDAwMDAwMDAwLCJkZXZpY2VJZCI6ImFuZHJvaWQtZml4dHVyZSIsInJldmlzaW9uIjo0",
            "Miwia2RmIjp7ImFsZ28iOiJwYmtkZjIiLCJpdGVyIjoxMDAwMDAsInNhbHQiOiJCd2dKQ2dzTURRNFBFQkVTRXhRVkZnPT0ifSwiY2lwaGVyIjoiYWVzLTI1Ni1nY20iLCJub25j",
            "ZSI6IkFHQ1ZGWDNmV1gvK3A2MjQiLCJpdGVtQ291bnRzIjp7InByb3ZpZGVycyI6MSwia2V5cyI6MSwiYWNjb3VudHMiOjAsIm1vZGVscyI6MCwicHJvZmlsZXMiOjB9fYnxiZL/",
            "83SZwcCKquUfZGWpTfzKIvqqCZxgd3JGl/oQmBGkeCjSGeILuWaizZoyRZndJ1xBaxBHQwkdcyXXWuK33iXEW2HGjdGY8jGUn2hkyB9rAlxTaY19mx8fpHudKT6aYdteaeUCRnH0",
            "w05AYPSs17J5FOIhRR/vyIrJddWMe7CbdSsO+ruCWRzZsgQV+xYOvybsFMfKyJ6HegT/rvoJaUr9rDDeHhYYAn7224Ux3NFdmYSdtqQVSkEam6s0wn/D/ktNl2cXLy0o8UtgFI3b",
            "MryIub93xPFzuYRQ8kEBTzYYvvJPhPicOktp18o3QxemFZAS4dzR1KElSAGYBuBFhFIGeGeiralDlj4oslXMPIiWGpQhtYn/7qmo9/Av7wFm25ZuF0xzslC79zPAKz2UiIzYx79q",
            "KGJWH2b/hHm/tE92F3wnfhIiw4aPLo0DdMFXtusd7Ah8zlcDV37PanGV/y0EyCMMuc0rUCpbasT5WUR52nCCpPsBIzNM4VuXrMWs5aRloG065tK5RMPk9kSCcn9tjeDaLjGxLctN",
            "9dWlOHL2/EgdrF1AkPFAwXw8zkYYj9eQdnNU7kRbyBtsXZo0JC2zZ1Rk3vzt4jornCgoXczoLbtKVXQghmHZ5dBV1IYDXFsr5gH73gOZePrKhuLf3TZtvnk/RI9aBTnJ6hU=",
        )
    }
}

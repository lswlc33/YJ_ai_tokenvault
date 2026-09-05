package com.lc33.tokenvault.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 7：脱敏（计划.md §14.3，红线 32）。
 *
 * 这是防泄漏的最后一道闸，所以用例照 M0.5 在三家真实中转站上实测到的**形态**来造
 * （fixture 在 `app/src/test/resources/fixtures/probe-matrix.json`）：
 * - base64 访问令牌**不匹配任何正则**，只能靠第一道已知明文值拦住。
 * - DeepSeek 的 401 回 `Your api key: ****alid is invalid`——**回显的是后 4 位**，
 *   只替换前缀完全挡不住。
 *
 * 两个 fixture 刻意用**拼接**构造：形状必须和真的一样（长度、`sk-` 前缀、base64 里的
 * `/` 与 `=`），但写成一整个字面量会被 `.githooks/pre-commit` 的 `sk-` 规则拦住——
 * 而那条规则拦的正是"真实密钥进仓库"，不该为了写测试去放宽它。
 * （这条注释是有来历的：第一版就是直接把 M0.5 用的真实值粘进来，被 hook 拦下了。）
 */
class RedactorTest {

    private val apiKey = "sk-" + "ExampleKeyDoNotUse" + "0123456789" + "abcdefghij" + "KLMNOPQR12"
    private val accessToken = "ExampleToken0000/ExampleTokenA9="
    private val redactor = Redactor({ listOf(apiKey, accessToken) })

    private fun assertClean(text: String, vararg mustNotContain: String) {
        val scrubbed = redactor.scrub(text)
        for (needle in mustNotContain) {
            assertFalse("脱敏后仍含 <$needle>：$scrubbed", scrubbed.contains(needle))
        }
    }

    @Test
    fun `整串密钥被擦掉`() {
        assertClean("Authorization: Bearer $apiKey", apiKey)
    }

    /** 只有正则时挡不住它——它不匹配 sk- 也不匹配 Bearer（红线 32 的原话）。 */
    @Test
    fun `base64 访问令牌靠已知明文值那一道拦住`() {
        val body = """{"data":{"access_token":"$accessToken","quota":226870}}"""
        assertClean(body, accessToken)
    }

    @Test
    fun `没有已知明文值时 base64 令牌确实漏得掉`() {
        // 把这条写成断言而不是注释：它是"第一道不能省"的证据。
        val onlyRegex = Redactor({ emptyList() })
        val scrubbed = onlyRegex.scrub("token was $accessToken here")
        assertTrue("这正是为什么第一道必须存在", scrubbed.contains(accessToken))
    }

    @Test
    fun `前缀回显被擦掉`() {
        assertClean("invalid key ${apiKey.take(8)}… rejected", apiKey.take(8))
    }

    /** M0.5 实测形态：上游掩掉前面、回显后 4 位。 */
    @Test
    fun `后缀回显被擦掉`() {
        val echoed = "Authentication Fails, Your api key: ****${apiKey.takeLast(4)} is invalid"
        assertClean(echoed, apiKey.takeLast(4))
    }

    @Test
    fun `sk- 形态靠正则兜底`() {
        val unknownKey = "sk-" + "abcdefghijklmnopqrstuvwxyz012345"
        assertFalse(redactor.scrub("key=$unknownKey").contains(unknownKey))
    }

    @Test
    fun `Bearer 后面的内容被擦掉`() {
        val scrubbed = redactor.scrub("Authorization: Bearer someOpaqueTokenValue123")
        assertFalse(scrubbed.contains("someOpaqueTokenValue123"))
    }

    @Test
    fun `JSON 凭据字段保留键名、只擦值`() {
        val scrubbed = redactor.scrub("""{"api_key":"whatever-value-here","quota":1}""")
        assertTrue("键名要留着，否则看不出是哪个字段泄的", scrubbed.contains("api_key"))
        assertFalse(scrubbed.contains("whatever-value-here"))
        assertTrue("非凭据字段不该被动", scrubbed.contains("\"quota\":1"))
    }

    @Test
    fun `邮箱被擦掉`() {
        val scrubbed = redactor.scrub("""{"email":"someone@example.com"}""")
        assertFalse(scrubbed.contains("someone@example.com"))
    }

    @Test
    fun `低熵后缀不误伤`() {
        val lowEntropy = Redactor({ listOf("password0000000000") })
        // 后缀 "0000" 只有一种字符，跳过；否则日志里所有 0000 都会被擦
        assertTrue(lowEntropy.scrub("run 0000 finished").contains("0000"))
    }

    @Test
    fun `带点的后缀不误伤`() {
        val emailSecret = Redactor({ listOf("account@example.com") })
        // 后缀 ".com" 含点，跳过；否则满屏的域名都会被擦成占位串
        assertTrue(emailSecret.scrub("host is api.deepseek.com").contains("deepseek.com"))
    }

    @Test
    fun `太短的秘密不进第一道`() {
        val shortSecret = Redactor({ listOf("1234") })
        assertTrue("四位数字当秘密会把日志擦花", shortSecret.scrub("count 1234").contains("1234"))
    }

    @Test
    fun `长的秘密先替换、不会被短的切碎`() {
        val prefix = apiKey.take(20)
        val both = Redactor({ listOf(prefix, apiKey) })
        val scrubbed = both.scrub("full=$apiKey")
        assertFalse(scrubbed.contains(prefix))
        assertFalse(scrubbed.contains(apiKey))
    }

    @Test
    fun `null 与空串不炸`() {
        assertEquals("", redactor.scrub(null))
        assertEquals("", redactor.scrub(""))
    }

    @Test
    fun `没有秘密的文本原样返回`() {
        val text = "GET /v1/models -> 200 in 412ms"
        assertEquals(text, redactor.scrub(text))
    }
}

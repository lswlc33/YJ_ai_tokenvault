package com.lc33.tokenvault.endpoint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从上游失败响应里抽"它自己写的那句原因"。
 *
 * 钉的是用户报的那个问题：安全访问令牌失效、余额返回 401，界面上只有一句"查询失败"。
 * 那句话其实一直都在 `balance_raw` 里躺着，只是从来没人抽出来。
 *
 * 这里给的全是**已脱敏**的文本（真实链路上由 `BalanceEngine.rawOf` 洗过），测试不碰脱敏
 * 本身——那是 `RedactorTest` 的活。
 */
class UpstreamMessageTest {

    @Test
    fun `new-api 那句令牌失效抽得出来`() {
        assertEquals(
            "安全访问令牌已失效",
            UpstreamMessage.of("""{"success":false,"message":"安全访问令牌已失效"}"""),
        )
    }

    @Test
    fun `OpenAI 形状从 error_message 里抽`() {
        assertEquals(
            "Invalid API key",
            UpstreamMessage.of("""{"error":{"type":"invalid_request_error","message":"Invalid API key"}}"""),
        )
    }

    @Test
    fun `error 直接是字符串时也认`() {
        assertEquals("upstream exploded", UpstreamMessage.of("""{"error":"upstream exploded"}"""))
    }

    @Test
    fun `msg 与 detail 两种写法都认`() {
        assertEquals("quota 已用尽", UpstreamMessage.of("""{"code":403,"msg":"quota 已用尽"}"""))
        assertEquals("site is paused", UpstreamMessage.of("""{"detail":"site is paused"}"""))
    }

    @Test
    fun `包在 data 里的错误也捞得到`() {
        assertEquals("令牌不存在", UpstreamMessage.of("""{"data":{"message":"令牌不存在"}}"""))
    }

    @Test
    fun `JSON 被截断时退化成按字段名扫`() {
        // `balance_raw` 只留头 8 KB，网关把一整页塞进 message 时闭合括号会被砍掉。
        // 这时候按字段名扫值，半句话也比什么都不说强。
        assertEquals(
            "安全访问令牌已失效",
            UpstreamMessage.of("""{"success":false,"message":"安全访问令牌已失效"""),
        )
    }

    @Test
    fun `抽不到的时候老实返回 null`() {
        assertNull(UpstreamMessage.of(null))
        assertNull(UpstreamMessage.of("   "))
        assertNull(UpstreamMessage.of("<html><title>502 Bad Gateway</title></html>"))
        assertNull(UpstreamMessage.of("""{"error":{"code":401}}"""))
        assertNull(UpstreamMessage.of("""{"ok":true}"""))
    }

    @Test
    fun `数字值不算原因`() {
        // `contentOrNull` 对 `{"message":123}` 也给 "123"，而那是要显示成
        // "上游说：123" 的噪声。两条路径都只认真正的字符串。
        assertNull(UpstreamMessage.of("""{"message":123}"""))
        assertNull(UpstreamMessage.of("""{"message":null}"""))
    }

    @Test
    fun `同前缀的键不会被误当成 message`() {
        assertNull(UpstreamMessage.of("""{"messageId":"abc-123"}"""))
    }

    @Test
    fun `换行压成一行并限长`() {
        assertEquals("登录已失效 请重新登录", UpstreamMessage.of("""{"message":"登录已失效\n  请重新登录"}"""))
        val long = "x".repeat(400)
        val hint = UpstreamMessage.of("""{"message":"$long"}""")!!
        assertEquals(200 + "…[truncated]".length, hint.length)
        assertEquals("x".repeat(200) + "…[truncated]", hint)
    }

    @Test
    fun `转义过的引号与斜杠还原`() {
        assertEquals(
            "bad \"token\" / path",
            UpstreamMessage.of("""{"message":"bad \"token\" / path"}"""),
        )
    }
}

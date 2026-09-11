package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest

/**
 * 余额适配器（§9.1）。
 *
 * 纯 Kotlin：`buildRequest` 产出 [ProbeRequest]（不碰 OkHttp），`parse` 把上游响应体
 * 解析成 [BalanceSnapshot]。真正发请求是 `net/` 层的事，所以这一层在 JVM 上用
 * 真实 fixture 就能全覆盖。
 *
 * 两个硬约束：
 * - **失败抛 [BalanceParseException]**（红线 8 的邻居）：解析不出字段要报错，不许静默
 *   降级成 `amount = null` 还当成功。但"缺 `quota_display_type`"这类可选字段不算失败。
 * - **响应体绝不进异常消息**（红线 32）：`/api/user/self` 会回显 `access_token`、邮箱、
 *   用户名，`BalanceParseException` 的消息里只能写"哪个字段缺失"，不能带原始 body。
 */
interface BalanceAdapter {
    val kind: BalanceKind

    /**
     * 构造请求。
     *
     * @param defaultKey 该供应商的默认 API Key 明文（`usesOwnToken = false` 的适配器用）。
     *   可能是 null（没有默认 Key）——此时适配器不该崩，而是产一个无鉴权请求（让上游
     *   用 401 告诉我们"没 Key 查不了"）。**是 `CharArray` 不是 `String`**（红线 1）：
     *   鉴权头要在 `net/` 层发出前才拼成字符串，拼完的临时 String 随请求头对象一起
     *   短命，明文的权威副本仍是调用方那份可擦的数组。
     * @param token `usesOwnToken = true` 的适配器用的独立访问令牌明文。
     */
    fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest

    /**
     * 解析响应。失败抛 [BalanceParseException]。
     *
     * @param status HTTP 状态码。非 2xx 时调用方通常不进来，进来了也按失败处理。
     * @param body 完整响应体。**不许截断**（§9.2：Agent Router 的 `/api/status` 是 5398 字节，
     *   关键字段排在公告之后）。
     */
    fun parse(status: Int, body: String): BalanceSnapshot
}

/**
 * 余额解析失败。
 *
 * [reason] 是**机器可读**的原因（缺哪个字段），**不带原始响应体**（红线 32）。UI 层
 * 拿到后翻译成本地化文案；原始 body 只在 [BalanceSnapshot.raw] 里、由详情页折叠区
 * 展示，绝不进日志或异常消息。
 */
class BalanceParseException(
    val kind: BalanceKind,
    val reason: String,
) : Exception("$kind: $reason")

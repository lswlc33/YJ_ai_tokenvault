package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.KeyHealth
import com.lc33.tokenvault.domain.ModelProbeState
import com.lc33.tokenvault.domain.ProbeLevel
import com.lc33.tokenvault.domain.ProbeOutcome
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 一次探测请求的分类结果（计划.md §8.4）。
 *
 * [health] 为 null 表示**这次不许改写持久结论**（红线 11）——瞬时失败（网络 / 429 / 5xx）
 * 只写 `lastOutcome` / `checkedAt` / 详情，`health` 与 `okAt` 保留上一次的值。
 *
 * [modelState] 非 null 时只作用于**模型行**（L3 的 NOT_FOUND），供应商与密钥的 health
 * 都不动（§8.4 第 5 / 13 行）。
 *
 * [detail] 是**上游原文**（截前 200 字符，调用方负责过 `Redactor`），不是本地化文案——
 * 本地化文案由 [reason] 承载，UI 层用 `strings.xml` 映射（红线 19：纯 Kotlin 层读不到资源）。
 */
data class Classification(
    val outcome: ProbeOutcome,

    /** 允许改写密钥 health 时非空；null = 红线 11，不许改。 */
    val health: KeyHealth? = null,

    /** 模型级结果，仅 L3 的"模型不存在"时非空。 */
    val modelState: ModelProbeState? = null,

    /** 上游原始 message 前 200 字符（脱敏后）。可能是空。 */
    val detail: String? = null,

    /** 需要本地化补充说明时非空。UI 据此在 detail 之外再拼一句。 */
    val reason: ClassificationReason? = null,

    /** 上游 HTTP 状态码。 */
    val httpStatus: Int? = null,

    /** 429 时建议的退避时间（毫秒），取响应头与 body 里 `retry_after` 的较大值。 */
    val retryAfterMs: Long? = null,
)

/**
 * 需要本地化解释的那几类结论（§8.4）。
 *
 * 只覆盖"上游没给原因 / 我们有自己的判断"这两种情况；上游给了原文的（401 无效令牌、
 * 额度耗尽等）直接用 `detail` 展示，不需要这个枚举。
 */
enum class ClassificationReason {
    /** 403 / 404 / 429 但响应体为空——UI 显示"上游未提供原因"而不是编一个（红线 35）。 */
    UpstreamGaveNoReason,

    /** 404（供应商级 L1）——路径不对。 */
    BadPath,

    /** 2xx 但返回 HTML 登录页 / 带 error 字段。 */
    UnexpectedContent,

    /** 400（L2）——密钥有效但请求参数不被接受。 */
    ParamRejectedButKeyValid,
}

/**
 * 错误分类矩阵（计划.md §8.4）的纯函数实现。
 *
 * **判定顺序不可变**，四条原则是 M0.5 实测逼出来的：
 * 1. 额度关键词优先于状态码——`403` + `该令牌额度已用尽` 是 INSUFFICIENT 不是 FORBIDDEN。
 * 2. 除 401/403 外的结构化响应都证明鉴权通过——L2 遇 400 是"密钥有效、请求要调"。
 * 3. 客户端校验关键词排在 401 **之前**——Agent Router 用 401 拦非白名单客户端。
 * 4. 响应体经常不是 JSON 甚至是空的——解析失败不能升级成 CONFIG_ERROR。
 *
 * 关键词匹配一律对 body 做**小写 + 去空白**后比，且只取前 2 KB（防一整页 HTML 拖垮正则）。
 */
object ProbeClassifier {

    /**
     * 客户端校验关键词（§8.2）。`unauthorized*` 两条是 M0.5 实测补上的——
     * Agent Router 用 `401 + unauthorized client detected` 拦非白名单客户端，缺了这两条
     * 会把"换个 UA 就能用"判成"密钥无效"。
     *
     * 这些是匹配**上游返回的协议内容**的，不是 UI 文案（i18n-exempt，理由同
     * `Protocol.ALIAS_NOISE`：把关键词搬进 strings.xml 会让"英文界面的用户匹配一段中文
     * 错误"失效）。
     *
     * **默认值**：用户可在设置 → 探测里编辑（§13.4），改过之后 [classify] 收到的
     * [clientKeywords] 参数就来自 `app_settings` 而不是这份常量。所以这份常量只是
     * "没改过时的初值"，不是权威（红线 31：配置项只有一个权威存储，这里是运行时传参）。
     */
    val DEFAULT_CLIENT_KEYWORDS = listOf(
        "unauthorized client", "unauthorized_client", "invalid client",
        "client not allowed", "forbidden client", "unsupported client",
        "user-agent", "ua 校验", "客户端", "claude code", "codex", "不支持该客户端", // i18n-exempt: 匹配上游响应，非 UI 文案
    )

    /** 额度耗尽关键词（§8.4 第 4 行）。 */
    private val QUOTA_KEYWORDS = listOf(
        "insufficient", "quota", "balance", "额度", "余额", "欠费", "已用尽", // i18n-exempt: 匹配上游响应，非 UI 文案
    )

    /** 模型不存在关键词（§8.4 第 5 行）。DeepSeek 返回 400 且列出支持的模型名。 */
    private val MODEL_KEYWORD_REGEX = Regex(
        "model.{0,20}(notfound|notexist|unsupported)|supportedapimodelnames|模型不存在|不支持的模型", // i18n-exempt: 匹配上游响应，非 UI 文案
        RegexOption.IGNORE_CASE,
    )

    fun classify(
        status: Int?,
        body: String?,
        error: Throwable?,
        level: ProbeLevel,
        clientKeywords: List<String> = DEFAULT_CLIENT_KEYWORDS,
    ): Classification {
        // 第 1 行：取消。
        if (error is kotlinx.coroutines.CancellationException) {
            return Classification(ProbeOutcome.CANCELLED)
        }

        // 第 2 行：网络类失败。都不许改写 health。
        if (error is SocketTimeoutException || error is ConnectException ||
            error is UnknownHostException || error is SSLException || error is IOException
        ) {
            return Classification(ProbeOutcome.NETWORK_ERROR, detail = error.message)
        }

        // 网络层没给状态码（理论上上面已覆盖），兜底按网络错误处理，不改写 health。
        val code = status ?: return Classification(
            ProbeOutcome.NETWORK_ERROR,
            detail = error?.message,
        )

        val normalized = normalizeBody(body)

        // 第 3 行：客户端校验关键词，**含 401**，排在鉴权之前。
        if (matchesAny(normalized, clientKeywords)) {
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                health = KeyHealth.CLIENT_BLOCKED,
                detail = truncate(body),
                httpStatus = code,
            )
        }

        // 第 4 行：402，或任意 4xx/5xx 且命中额度关键词。
        if (code == 402 || (code in 400..599 && matchesAny(normalized, QUOTA_KEYWORDS))) {
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                health = KeyHealth.INSUFFICIENT,
                detail = truncate(body),
                httpStatus = code,
            )
        }

        // 第 5 行：L3 且模型不存在关键词。
        if (level == ProbeLevel.L3_MODEL && code in 400..499 &&
            MODEL_KEYWORD_REGEX.containsMatchIn(normalized)
        ) {
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                modelState = ModelProbeState.NOT_FOUND,
                detail = truncate(body),
                httpStatus = code,
            )
        }

        // 第 6 行：401。
        if (code == 401) {
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                health = KeyHealth.UNAUTHORIZED,
                detail = truncate(body),
                httpStatus = code,
            )
        }

        // 第 7 行：403。body 为空时标"上游未提供原因"（红线 35）。
        if (code == 403) {
            val bodyText = truncate(body)
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                health = KeyHealth.FORBIDDEN,
                detail = bodyText,
                reason = if (bodyText == null) ClassificationReason.UpstreamGaveNoReason else null,
                httpStatus = code,
            )
        }

        // 第 8 行：429。退避时间取响应头与 body 里 retry_after 的较大值。
        if (code == 429) {
            val bodyText = truncate(body)
            return Classification(
                ProbeOutcome.RATE_LIMITED,
                detail = bodyText,
                reason = if (bodyText == null) ClassificationReason.UpstreamGaveNoReason else null,
                httpStatus = code,
                retryAfterMs = parseRetryAfter(body),
            )
        }

        // 第 9 行：5xx。
        if (code in 500..599) {
            return Classification(
                ProbeOutcome.UPSTREAM_ERROR,
                detail = truncate(body),
                httpStatus = code,
            )
        }

        // 第 11 / 12 行：400。
        if (code == 400) {
            return when (level) {
                ProbeLevel.L2_KEY_VALIDITY -> Classification(
                    ProbeOutcome.SUCCESS,
                    health = KeyHealth.OK,
                    detail = truncate(body),
                    reason = ClassificationReason.ParamRejectedButKeyValid,
                    httpStatus = code,
                )
                else -> Classification(
                    ProbeOutcome.CONCLUSIVE_FAIL,
                    health = KeyHealth.CONFIG_ERROR,
                    detail = truncate(body),
                    httpStatus = code,
                )
            }
        }

        // 第 13 行：404（模型级 L3）。
        if (code == 404 && level == ProbeLevel.L3_MODEL) {
            val bodyText = truncate(body)
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                modelState = ModelProbeState.NOT_FOUND,
                detail = bodyText,
                reason = if (bodyText == null) ClassificationReason.UpstreamGaveNoReason else null,
                httpStatus = code,
            )
        }

        // 第 14 行：404（供应商级 L1）。
        if (code == 404) {
            return Classification(
                ProbeOutcome.CONCLUSIVE_FAIL,
                health = KeyHealth.CONFIG_ERROR,
                detail = truncate(body),
                reason = ClassificationReason.BadPath,
                httpStatus = code,
            )
        }

        // 第 15 / 16 行：2xx。
        if (code in 200..299) {
            val isErrorJson = body?.trim()?.let { hasErrorField(it) } ?: false
            return if (isErrorJson) {
                Classification(
                    ProbeOutcome.CONCLUSIVE_FAIL,
                    health = KeyHealth.CONFIG_ERROR,
                    reason = ClassificationReason.UnexpectedContent,
                    httpStatus = code,
                )
            } else {
                Classification(
                    ProbeOutcome.SUCCESS,
                    health = KeyHealth.OK,
                    httpStatus = code,
                )
            }
        }

        // 兜底：任何没覆盖到的状态码按配置错误处理。
        return Classification(
            ProbeOutcome.CONCLUSIVE_FAIL,
            health = KeyHealth.CONFIG_ERROR,
            detail = truncate(body),
            httpStatus = code,
        )
    }

    /** 判 2xx 是否"带 error 字段"（§8.4 第 16 行）。宽松判断，不要求完整 JSON。 */
    private fun hasErrorField(json: String): Boolean {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return false
        return Regex("\"error\"\\s*:\\s*(?!null)").containsMatchIn(trimmed)
    }

    /** 小写 + 去空白，且只取前 2 KB。关键词匹配都在这份"紧凑"文本上做。 */
    private fun normalizeBody(body: String?): String {
        if (body.isNullOrEmpty()) return ""
        val limited = body.take(MAX_BODY_SCAN)
        return limited.lowercase().filterNot { it.isWhitespace() }
    }

    /** 关键词也去空白再比，否则 `unauthorized client` 在去空白的 body 里永远匹配不上。 */
    private fun matchesAny(normalized: String, keywords: List<String>): Boolean =
        keywords.any { normalized.contains(it.lowercase().filterNot(Char::isWhitespace)) }

    private fun truncate(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return body.trim().take(200)
    }

    /** 429 的退避时间：从 body 里 `retry_after` 提取（响应头里的由 net 层并入后比较）。 */
    private fun parseRetryAfter(body: String?): Long? {
        val seconds = body?.let { b ->
            Regex("retry_after[\"']?\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(b)?.groupValues?.get(1)?.toLongOrNull()
        }
        return seconds?.let { it * 1000 }
    }

    private const val MAX_BODY_SCAN = 2048
}

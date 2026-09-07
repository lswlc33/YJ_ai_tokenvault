package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.AuthStyle
import com.lc33.tokenvault.domain.Protocol

/**
 * 三协议的请求构造（计划.md §5.2 那张表 + §8.3 的 L1/L2）。
 *
 * 全部是纯函数，输入是规范化后的端点集合 + 鉴权材料，输出 [ProbeRequest]。不碰网络、
 * 不碰 OkHttp，所以测试 2 用 `MockWebServer` 就能验证真实发出的报文。
 *
 * 三条贯穿所有协议的约定：
 * - **`max_tokens = 16`**：只够保证请求被接受，不保证拿到内容——M0.5 实测推理模型的
 *   200 响应经常是 `content: ""` 或 `status: "incomplete"`，判 SUCCESS 的代码不能要求
 *   文本非空（红线 34）。
 * - **不带 `stream`**：探测要的是同步结果，流式会把响应变成分块，白添复杂度。
 * - **鉴权头由协议控制**（红线 22 后半句）：`CHAT`/`RESPONSES` 用 `Authorization: Bearer`，
 *   `ANTHROPIC` 用 `x-api-key` + `anthropic-version`。客户端预设里的同名键要被忽略。
 */
object ProbeRequestBuilder {

    /** 极简探测的 `max_tokens`（§5.2）。 */
    const val MINIMAL_MAX_TOKENS = 16

    /** Anthropic 协议要求的版本头（§5.2）。 */
    const val ANTHROPIC_VERSION = "2023-06-01"

    /**
     * L1：拉模型列表。`GET {models}`，零成本。
     *
     * @param apiKey 密钥明文，可为 null——**不鉴权基线检测**（§8.3 红线 12 落地）就用
     *   `Authorization: Bearer yj-probe-invalid` 发一次，若也 200 说明这家 models 路由不鉴权。
     */
    fun modelsList(
        modelsUrl: String,
        protocol: Protocol,
        apiKey: CharArray?,
        authStyle: AuthStyle? = null,
    ): ProbeRequest {
        val headers = authHeaders(protocol, apiKey, authStyle)
        return ProbeRequest(
            method = "GET",
            url = modelsUrl,
            headers = headers,
            protocol = protocol,
        )
    }

    /**
     * L2 / L3：用该 Key 发一次极简推理调用。
     *
     * L2 是"用这张 Key 发 L1"（零成本）；但若该站 models 路由不鉴权，L2 只能升级成
     * 这里的一次真实调用（要钱，红线 36）。L3 逐模型预热也走这里。
     */
    fun inference(
        url: String,
        protocol: Protocol,
        apiKey: CharArray?,
        modelId: String,
        authStyle: AuthStyle? = null,
    ): ProbeRequest {
        val headers = authHeaders(protocol, apiKey, authStyle)
        val body = when (protocol) {
            Protocol.CHAT -> chatBody(modelId)
            Protocol.RESPONSES -> responsesBody(modelId)
            Protocol.ANTHROPIC -> anthropicBody(modelId)
        }
        return ProbeRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = body,
            protocol = protocol,
        )
    }

    /**
     * 鉴权头。密钥为空时给一个明确的"无效探测值"，而不是干脆不带头——
     * 不带头的请求会走 OkHttp 默认，而 §8.3 的基线检测需要"带着一个坏 Bearer"才能测出
     * 这家到底鉴不鉴权。
     *
     * [style] 为 null 时用协议默认；非 null 时强制用指定风格（嗅探换鉴权头、以及尊重
     * `provider.authStyle` 都走这里）。`anthropic-version` 是协议头而不是鉴权头，所以
     * ANTHROPIC 无论哪种风格都带上它。
     */
    fun authHeaders(protocol: Protocol, apiKey: CharArray?, style: AuthStyle? = null): List<Pair<String, String>> {
        val token = apiKey?.let { String(it) } ?: INVALID_PROBE_TOKEN
        val effective = when (style) {
            AuthStyle.BEARER, AuthStyle.X_API_KEY -> style
            else -> protocol.defaultAuthStyle
        }
        val headers = mutableListOf<Pair<String, String>>()
        when (effective) {
            AuthStyle.BEARER -> headers += "Authorization" to "Bearer $token"
            AuthStyle.X_API_KEY -> headers += "x-api-key" to token
            AuthStyle.AUTO -> headers += "Authorization" to "Bearer $token"
        }
        if (protocol == Protocol.ANTHROPIC) {
            headers += "anthropic-version" to ANTHROPIC_VERSION
        }
        return headers
    }

    /** 不鉴权基线检测用的占位令牌。选这个值是因为它一眼可辨、不会被当成真密钥。 */
    const val INVALID_PROBE_TOKEN = "yj-probe-invalid"

    // ------------------------------------------------------------------ 极简 body

    private fun chatBody(modelId: String): String =
        """{"model":"$modelId","messages":[{"role":"user","content":"ping"}],"max_tokens":$MINIMAL_MAX_TOKENS}"""

    private fun responsesBody(modelId: String): String =
        """{"model":"$modelId","input":"ping","max_output_tokens":$MINIMAL_MAX_TOKENS}"""

    private fun anthropicBody(modelId: String): String =
        """{"model":"$modelId","max_tokens":$MINIMAL_MAX_TOKENS,"messages":[{"role":"user","content":"ping"}]}"""
}

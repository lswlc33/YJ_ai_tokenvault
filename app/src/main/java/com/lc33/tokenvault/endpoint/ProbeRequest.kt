package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.Protocol

/**
 * 一次探测请求的**纯数据**描述（计划.md §8.1）。
 *
 * `endpoint/` 只产出这个，不碰任何 OkHttp 类型——协议构造与解析在 JVM 上用
 * `MockWebServer` 就能做真实测试。真正把它发出去的是 `net/OkHttpEngine`。
 *
 * @param body 请求体。L1 的 `GET` 是 null；L2/L3 的极简推理调用是 JSON 字符串。
 *   **这里不区分秘密与非秘密**——密钥在 [headers] 里，它已经在 `CharArray` 层面被
 *   `net/` 层接过一次，这里的头值是最终要发出去的形态。
 */
data class ProbeRequest(
    val method: String,
    val url: String,

    /**
     * 有序头。**必须含 `User-Agent`**——不设时 OkHttp 会自带 `okhttp/4.x`，等于直接
     * 告诉上游"我不是 AI 客户端"（§8.1）。鉴权头（`Authorization` / `x-api-key` /
     * `anthropic-version`）由协议构造代码控制，客户端预设不得覆盖（红线 22）。
     */
    val headers: List<Pair<String, String>>,
    val body: String? = null,

    /** 这个请求服务于哪个协议。`net/` 层与嗅探靠它决定重试策略。 */
    val protocol: Protocol? = null,
)

/**
 * 一次探测请求的结果（计划.md §8.1）。
 *
 * [body] 是原始响应体字符串。**入库 / 落日志前必须过 `Redactor.scrub`**（红线 32），
 * 因为上游经常在错误消息里回显密钥的后 4 位（`probe-matrix.json` 的 `deepseek-invalid-key-401`）。
 *
 * [latencyMs] 只测到**首字节**，不把整个 body 读进来计时——body 可能很大，
 * 而延迟的语义是"上游多久开始回"。
 */
data class ProbeResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val latencyMs: Long? = null,
    /** 网络层异常（超时 / DNS / TLS / 连接失败）时非空，[status] 无意义。 */
    val error: Throwable? = null,
)

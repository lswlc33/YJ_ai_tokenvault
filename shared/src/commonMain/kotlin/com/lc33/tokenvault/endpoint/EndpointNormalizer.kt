package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.Protocol

/** 规范化后的端点集合。UI 的"实时预览"直接画它。 */
data class ApiEndpointSet(
    /** 去掉版本段之后的根，例如 `https://ps.air-outer.com`。 */
    val apiRoot: String,
    /** 版本段，例如 `v1`。用户没填就是默认值。 */
    val ver: String,
    /** `scheme://host[:port]`，余额查询地址的默认值。 */
    val origin: String,
    /** 每个协议的完整 URL，已应用 `pathOverrides`。 */
    val byProtocol: Map<Protocol, String>,
    /** 模型列表 URL。 */
    val modelsUrl: String,
    /** 地址是 `http://` —— 调用方必须要求用户显式打开 `allowInsecure`（§7.5）。 */
    val insecure: Boolean,
)

/** 规范化失败的原因。每一条都要有对应的 UI 文案，不允许出现"未知错误"。 */
enum class EndpointError {
    Empty,
    UnsupportedScheme,

    /** 带 query 或 fragment。静默丢掉参数会得到一个看起来对、其实永远 401 的端点。 */
    HasQueryOrFragment,
    NoHost,
}

sealed interface NormalizeResult {
    data class Ok(val endpoints: ApiEndpointSet) : NormalizeResult
    data class Err(val error: EndpointError) : NormalizeResult
}

private val VERSION_SEGMENT = Regex("""^v\d+(beta|alpha)?$""")
private const val DEFAULT_VER = "v1"

/**
 * 已知的"端点尾巴"（按段匹配，从尾部往前剥）。
 *
 * 用户会把**完整请求地址**当 API 请求地址贴进来：`https://host/v1/chat/completions`。
 * 不剥的话规范化得到 `apiRoot = https://host/v1/chat/completions`，再拼上协议路径就成了
 * `https://host/v1/chat/completions/v1/chat/completions`——上游回 404，而明细页只写
 * "路径不对"，用户看不出自己错在哪。cURL 导入那条路已经会剥（`importer/CurlParser`），
 * 手输这条路也必须剥，否则同一个地址"导入能用、手输不能用"。
 *
 * 只认这几条真实存在的尾巴，不做"凡末段像资源名就砍"的泛化：`https://host/api` 这种
 * 自建前缀要原样留着，砍错了拼出来的地址反而更奇怪，而且没人会来报 bug。
 */
private val KNOWN_ENDPOINT_SUFFIXES = listOf(
    listOf("chat", "completions"),
    listOf("completions"),
    listOf("responses"),
    listOf("messages"),
    listOf("models"),
)

/** 尾巴匹配上了就剥掉，只剥一次（`.../v1/chat/completions` 剥完就剩 `.../v1`）。 */
private fun stripEndpointSuffix(segments: List<String>): List<String> {
    val suffix = KNOWN_ENDPOINT_SUFFIXES.firstOrNull { candidate ->
        candidate.size <= segments.size && segments.takeLast(candidate.size) == candidate
    } ?: return segments
    return segments.dropLast(suffix.size)
}

/**
 * 把 `pathOverrides` 的一条覆盖路径修成"能直接接在 apiRoot 后面"的形态。
 *
 * 必须以 `/` 开头：少了它，`apiRoot + suffix` 会拼成 `https://hostv1/chat/completions`
 * ——host 段被吃掉一截，Ktor 把它当相对路径再解析一次，得到一个看起来能发、实际永远
 * 404 的地址。用户在设置页输入时漏掉开头的斜杠是常态（浏览器地址栏不需要），所以这里
 * 补齐而不是报错：错的只是格式，意图很明确。
 */
private fun normalizeOverride(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
}

/**
 * 把用户填的"API 请求地址"规范化成一组端点（计划.md §5.2）。
 *
 * 手写解析而不是用 `java.net.URI`：这里要**主动拒绝** query 与 fragment，而 `URI`
 * 会安静地把它们解析进字段里，很容易在后面某一步被忘掉。`https://host/v1?key=abc`
 * 这种粘贴很常见，静默丢掉 `?key=abc` 得到的是一个看起来对、实际永远 401 的端点，
 * 而用户完全没有线索。所以宁可在这里报错，并且错误文案要指向"请只填到路径部分"。
 *
 * Azure OpenAI 那种必须带 `?api-version=` 的形态**不在支持范围内**，这是刻意的：
 * 支持它就得让 query 成为合法输入，那上面这条保护就没了。
 */
fun normalizeBaseUrl(
    input: String,
    pathOverrides: Map<Protocol, String> = emptyMap(),
): NormalizeResult {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return NormalizeResult.Err(EndpointError.Empty)
    if (trimmed.contains('?') || trimmed.contains('#')) {
        return NormalizeResult.Err(EndpointError.HasQueryOrFragment)
    }

    val hasScheme = trimmed.contains("://")
    val scheme = if (hasScheme) trimmed.substringBefore("://").lowercase() else "https"
    if (scheme != "http" && scheme != "https") {
        return NormalizeResult.Err(EndpointError.UnsupportedScheme)
    }
    val afterScheme = if (hasScheme) trimmed.substringAfter("://") else trimmed
    val hostPort = afterScheme.substringBefore('/')
    if (hostPort.isEmpty()) return NormalizeResult.Err(EndpointError.NoHost)

    val path = afterScheme.substringAfter('/', missingDelimiterValue = "").trimEnd('/')
    val rawSegments = path.split('/').filter { it.isNotEmpty() }

    // 先剥端点尾巴（用户把完整请求地址当 baseUrl 贴进来的那种），再认版本段：
    // `https://host/v1/chat/completions` → [v1] → apiRoot = https://host、ver = v1。
    val segments = stripEndpointSuffix(rawSegments)

    // 尾段是版本号就剥掉。openrouter.ai/api/v1 这种带子路径的也对：剥掉 v1 得
    // openrouter.ai/api，拼回去还是对的。
    val versionSegment = segments.lastOrNull()?.takeIf { VERSION_SEGMENT.matches(it) }
    val rootSegments = if (versionSegment == null) segments else segments.dropLast(1)

    val origin = "$scheme://$hostPort"
    val apiRoot = if (rootSegments.isEmpty()) origin else origin + "/" + rootSegments.joinToString("/")
    val ver = versionSegment ?: DEFAULT_VER

    val byProtocol = Protocol.entries.associateWith { protocol ->
        val suffix = normalizeOverride(pathOverrides[protocol])
            ?: protocol.defaultPathTemplate.replace("{ver}", ver)
        apiRoot + suffix
    }

    return NormalizeResult.Ok(
        ApiEndpointSet(
            apiRoot = apiRoot,
            ver = ver,
            origin = origin,
            byProtocol = byProtocol,
            modelsUrl = "$apiRoot/$ver/models",
            insecure = scheme == "http",
        ),
    )
}

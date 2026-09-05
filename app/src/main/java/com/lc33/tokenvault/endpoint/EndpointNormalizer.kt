package com.lc33.tokenvault.endpoint

/**
 * 协议。属于**模型**，不属于供应商（红线 18）。
 *
 * 默认路径里的 `{ver}` 由 [normalizeBaseUrl] 从用户填的地址里剥出来的版本段替换。
 */
enum class Protocol(val defaultPath: String) {
    CHAT("/{ver}/chat/completions"),
    RESPONSES("/{ver}/responses"),
    ANTHROPIC("/{ver}/messages"),
}

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
    val segments = path.split('/').filter { it.isNotEmpty() }

    // 尾段是版本号就剥掉。openrouter.ai/api/v1 这种带子路径的也对：剥掉 v1 得
    // openrouter.ai/api，拼回去还是对的。
    val versionSegment = segments.lastOrNull()?.takeIf { VERSION_SEGMENT.matches(it) }
    val rootSegments = if (versionSegment == null) segments else segments.dropLast(1)

    val origin = "$scheme://$hostPort"
    val apiRoot = if (rootSegments.isEmpty()) origin else origin + "/" + rootSegments.joinToString("/")
    val ver = versionSegment ?: DEFAULT_VER

    val byProtocol = Protocol.entries.associateWith { protocol ->
        val suffix = pathOverrides[protocol] ?: protocol.defaultPath.replace("{ver}", ver)
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

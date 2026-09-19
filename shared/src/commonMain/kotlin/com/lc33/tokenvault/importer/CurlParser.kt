package com.lc33.tokenvault.importer

/**
 * cURL 导入解析器（§8.2 推荐路径）。
 *
 * 纯函数：`parse(text)` 不碰 Android / 网络 / 时间。用户用 mitmproxy / Charles / Fiddler
 * 抓一次真实客户端的请求，复制成 cURL 粘进来，这里把它转成一个客户端预设草稿。
 *
 * 三条职责（§8.2）：
 * 1. 解析 `-H/--header`、`-A/--user-agent`、`-X/--request`、`-d/--data/--data-raw/--data-binary`、
 *    `--compressed`。长选项的等号写法（`--header=X`）与值粘在短选项后面的写法
 *    （`-XPOST`、`-d{...}`）都先拆成"选项 + 值"两个 token 再走同一套分支。
 * 2. 处理续行（`\` 与 Windows 的 `^`）、单双引号、`$'…'` 转义形式。`$'…'` 是 Bash 的
 *    ANSI-C quoting，Fiddler / mitmproxy 的"Copy as cURL"会把整段 raw request 塞进去
 *    （`$'POST /x HTTP/1.1\r\nHost: …'`），所以**转义还原在 tokenize 这一层做**——
 *    外壳在那里就被剥掉了，等到 parseHeader 已经无从判断哪些反斜杠本来是转义。
 * 3. **自动剔除** `Authorization` / `x-api-key` / `cookie` / `content-length` / `host` /
 *    `connection` / `accept-encoding`（这几个由代码或 OkHttp 接管），并在结果里明确告知
 *    剔除了哪些，供预览页展示。
 *
 * 另一条隐含职责：从 body 里推断 `bodyPatch`——只保留**非模型 / 非消息**的辅助字段
 * （如 `store` / `metadata` / `stream_options`），不把别人的 prompt / model 带进来。
 * 这放在 [CurlParser.inferBodyPatch] 里，独立可测。
 */
object CurlParser {

    /** 代码 / OkHttp 接管的头，cURL 里出现就剔除并在结果里告知。 */
    private val DROPPED_HEADERS = setOf(
        "authorization", "x-api-key", "cookie", "content-length", "host",
        "connection", "accept-encoding", "content-type",
    )

    /** body 里要剔除的"业务字段"，剩下的才进 bodyPatch（§8.2：不把别人的 prompt 带进来）。 */
    private val BODY_EXCLUDED_KEYS = setOf(
        "model", "messages", "input", "prompt", "max_tokens", "max_output_tokens",
        "stream", "temperature", "top_p", "system",
    )

    /**
     * 允许"等号传值 / 值直接粘在选项后"的选项名，只列解析器真吃得下的那些。
     * 其余选项（`--compressed`、`-s`、`--data-urlencode`）本来就该整 token 放过，
     * 拆它们只会把 URL 的 query 与不认识的值搅进解析结果。
     */
    private val INLINE_OPTIONS = setOf(
        "-H", "--header", "-A", "--user-agent", "-X", "--request",
        "-d", "--data", "--data-raw", "--data-binary",
    )

    /** `$'…'` 里认得的转义（Bash ANSI-C quoting 的常用子集）。认不出的原样保留反斜杠。 */
    private val ANSI_C_ESCAPES = mapOf(
        'n' to '\n',
        't' to '\t',
        'r' to '\r',
        '\\' to '\\',
        '\'' to '\'',
        '"' to '"',
    )

    fun parse(text: String): CurlResult {
        val normalized = normalizeMarkdownLinks(text)
        val tokens = expandInlineOptions(tokenize(normalized))
        val headers = mutableListOf<Pair<String, String>>()
        var userAgent: String? = null
        var method: String? = null
        var dataBody: String? = null
        val dropped = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok == "-H" || tok == "--header" -> {
                    val raw = tokens.getOrNull(i + 1) ?: break
                    headers += parseHeader(raw)
                    i += 2
                }
                tok == "-A" || tok == "--user-agent" -> {
                    userAgent = tokens.getOrNull(i + 1)
                    i += 2
                }
                tok == "-X" || tok == "--request" -> {
                    // 统一大写：curl 允许 `-X post`，而下游（预设落库 / 预览展示）比较的是
                    // `POST`。留着小写就成了"同一条命令两次解析结果不一样"的隐形差异。
                    method = tokens.getOrNull(i + 1)?.uppercase()
                    i += 2
                }
                tok == "-d" || tok == "--data" || tok == "--data-raw" || tok == "--data-binary" -> {
                    val raw = tokens.getOrNull(i + 1) ?: break
                    // **拼接而不是覆盖**：curl 自己的语义就是把多个 `-d` 用 `&` 串成同一个
                    // body（`-d a=1 -d b=2` → `a=1&b=2`）。旧实现后一条吃掉前一条，
                    // 分段导出的 raw request 就只剩最后一段，bodyPatch 跟着缺字段。
                    dataBody = dataBody?.let { "$it&$raw" } ?: raw
                    i += 2
                }
                tok == "--compressed" -> i += 1
                else -> i += 1
            }
        }

        val url = tokens.firstOrNull { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        val apiKey = headers.firstNotNullOfOrNull { (key, value) -> apiKeyOf(key, value) }
        val model = dataBody?.let(::modelOf)

        // 剔除代码 / OkHttp 接管的头，并记下剔除的键（供预览页告知）
        val kept = mutableListOf<Pair<String, String>>()
        for ((key, value) in headers) {
            if (key.lowercase() in DROPPED_HEADERS) {
                dropped += key
            } else {
                kept += key to value
            }
        }

        val bodyPatch = dataBody?.let { inferBodyPatch(it) } ?: "{}"

        return CurlResult(
            url = url,
            apiKey = apiKey,
            model = model,
            userAgent = userAgent,
            method = method,
            dataBody = dataBody,
            headers = kept,
            bodyPatch = bodyPatch,
            droppedHeaders = dropped,
        )
    }

    /** 聊天工具复制出来的 Markdown 链接（`[https://x](https://x)`）先还原成裸 URL。 */
    private fun normalizeMarkdownLinks(text: String): String =
        text.replace(MARKDOWN_URL) { it.groupValues[1] }

    /** 鉴权头 → API Key；Bearer 去前缀，其余风格原样保留。 */
    private fun apiKeyOf(key: String, value: String): CharArray? {
        return when (key.lowercase()) {
            "authorization" -> value
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.toCharArray()
            "x-api-key" -> value.trim().takeIf { it.isNotEmpty() }?.toCharArray()
            else -> null
        }
    }

    /** body 里的 `model` 字段。不是合法 JSON 对象时静略过——cURL 里也可能带表单。 */
    private fun modelOf(body: String): String? {
        val obj = runCatching { patchJson.parseToJsonElement(body) }.getOrNull()
            as? kotlinx.serialization.json.JsonObject ?: return null
        return (obj["model"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private val MARKDOWN_URL = Regex("""\[[^\]]*\]\((https?://[^)\s]+)\)""")
    // ------------------------------------------------------------------ 词法

    /**
     * 把 cURL 命令切成 token，处理续行与引号。
     *
     * 续行：行尾 `\`（POSIX）或 `^`（Windows）表示下一行接着当前参数。切掉续行符后，
     * 引号内的空白不算分隔符（`-H "x-app: cli"` 是一个 token，不是两个）。
     */
    private fun tokenize(text: String): List<String> {
        // 先按续行把物理行合成逻辑行：行尾的 `\` / `^` 删掉并拼接下一行。
        val joined = text
            .replace(Regex("""\\\s*\r?\n"""), " ")
            .replace(Regex("""\^\s*\r?\n"""), " ")
            .replace("\r\n", "\n")

        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var dollarQuote = false // `$'…'`：Bash 的 ANSI-C quoting
        var inToken = false

        var i = 0
        while (i < joined.length) {
            val ch = joined[i]
            when {
                quote != null -> {
                    // Bash 双引号里 `\"` / `\\` / `\$` 会被 shell 还原；JSON body 最常见的是 `\"`。
                    if (quote == '"' && ch == '\\' && i + 1 < joined.length &&
                        joined[i + 1] in "\"\\\$`"
                    ) {
                        current.append(joined[i + 1])
                        i++
                    } else if (ch == quote) {
                        quote = null
                    } else {
                        current.append(ch)
                    }
                }
                dollarQuote -> {
                    // ANSI-C quoting：**在这里**把 `\n` / `\t` 等还原成真字符。
                    // `$'…'` 的外壳被剥掉之后，token 里已经不再有"这是带转义的引号串"的
                    // 痕迹，`unescapeShell` 那层事后补不回来（曾经的漏网：Fiddler 风格
                    // `$'POST /x HTTP/1.1\r\nHost: …'` 整段原样落进 body，`\r\n` 还是两个字面字符）。
                    when {
                        ch == '\'' -> dollarQuote = false
                        ch == '\\' && i + 1 < joined.length -> {
                            val unescaped = ANSI_C_ESCAPES[joined[i + 1]]
                            if (unescaped != null) {
                                current.append(unescaped)
                                i++ // 反斜杠吃掉下一个字符
                            } else {
                                // 认不出的转义**原样保留**（含反斜杠）：猜语义不如不猜，
                                // Windows 路径 `C:\a` 这类字面反斜杠才不会凭空消失。
                                current.append(ch)
                            }
                        }
                        else -> current.append(ch)
                    }
                }
                // `$'…'`：`$` 后紧跟 `'` 才是一对（避免把普通 `$` 误判成引号）
                ch == '$' && i + 1 < joined.length && joined[i + 1] == '\'' -> {
                    dollarQuote = true
                    inToken = true
                    i++ // 跳过那个 `'`
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    inToken = true
                }
                ch.isWhitespace() -> {
                    if (inToken) {
                        tokens += current.toString()
                        current.clear()
                        inToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    inToken = true
                }
            }
            i++
        }
        if (inToken) tokens += current.toString()
        // 引号没闭合（`$'…` 少了收尾的 `'`）时上面的循环会把 dollarQuote 留成 true。
        // 结果照原样吐出去即可——半截命令比崩掉更有用，预览页会显示解析出来的那部分。

        return tokens
    }

    /**
     * 把"值粘在选项里"的两种写法拆成两个 token：
     * - 长选项的等号形式：`--header=x-app: cli` → `--header`, `x-app: cli`。
     * - 短选项直接跟值：`-XPOST` → `-X`, `POST`；`-d{...}` → `-d`, `{...}`。
     *
     * 只认 [INLINE_OPTIONS] 里的那些选项名，其余带 `=` 或短横线的 token 原样放过——
     * URL 的 query（`?a=1`）与 `--data-urlencode` 都不该被动过。
     */
    private fun expandInlineOptions(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        for (tok in tokens) {
            val eq = tok.indexOf('=')
            val longName = if (tok.startsWith("--") && eq > 0) tok.substring(0, eq) else null
            val shortName = if (tok.length > 2 && tok[0] == '-' && tok[1] != '-') tok.substring(0, 2) else null
            when {
                longName != null && longName in INLINE_OPTIONS -> {
                    out += longName
                    out += tok.substring(eq + 1)
                }
                shortName != null && shortName in INLINE_OPTIONS && tok.length > 2 -> {
                    out += shortName
                    out += tok.substring(2)
                }
                else -> out += tok
            }
        }
        return out
    }

    /**
     * 解析一个 `-H` 的参数为 (key, value)。
     *
     * 普通形式按第一个 `:` 切分，`key` 与 `value` 都去空白。`$'…'` 的转义**正常情况下
     * 已在 tokenize 还原**；这里再走一遍 [unescapeShell] 只为覆盖"外壳被另一层引号保住"
     * 的写法（`-H "$'x-app: cli'"`），那种 token 里确实还带着 `$'` 字面量。
     */
    private fun parseHeader(raw: String): Pair<String, String> {
        val body = unescapeShell(raw)
        val colon = body.indexOf(':')
        if (colon < 0) return body.trim() to ""
        return body.substring(0, colon).trim() to body.substring(colon + 1).trim()
    }

    /**
     * 剥掉 `$'…'` 外壳并还原常见转义。
     *
     * 注意与 [tokenize] 的分工：正常路径上 `$'…'` 的外壳与转义都在 tokenize 处理掉了
     * （那里才知道引号到底哪一对），这个函数只是上面注释里那种"外层还有一对引号"的补漏。
     */
    private fun unescapeShell(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("$'") && s.endsWith("'")) {
            s = s.substring(2, s.length - 1)
            return s
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\'", "'")
                .replace("\\\\", "\\")
        }
        return s
    }

    // ------------------------------------------------------------------ bodyPatch

    /**
     * 从 cURL 的 body 推断 `bodyPatch`（§8.2）。
     *
     * 只保留**非模型 / 非消息**的辅助字段。理由：cURL 里往往是某个真实调用，
     * 带着别人的 prompt 与 model id，直接存成 bodyPatch 会把它们一起叠到探测 body 上。
     * 所以这里把 [BODY_EXCLUDED_KEYS] 里的业务字段删掉，剩下的（如 `store`、`metadata`、
     * `stream_options`）才是"这个客户端总会带上的指纹字段"。
     *
     * 解析失败（不是合法 JSON 对象）时返回 `"{}"`——bodyPatch 是可选项，宁可没有也不崩溃。
     */
    fun inferBodyPatch(body: String): String {
        val element = runCatching { patchJson.parseToJsonElement(body) }.getOrNull() ?: return "{}"
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return "{}"
        // 只保留非业务字段：删掉 model / messages / prompt 这些"这次调用的具体内容"，
        // 剩下的（store / metadata / stream_options 等）才是客户端总会带上的指纹字段。
        val kept = obj.filterKeys { it !in BODY_EXCLUDED_KEYS }
        if (kept.isEmpty()) return "{}"
        return kotlinx.serialization.json.JsonObject(kept).toString()
    }

    private val patchJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}

/** cURL 解析结果：一个客户端预设草稿，还没落库。 */
data class CurlResult(
    /** 请求 URL。没有 URL 时为 null（导入器据此把这条命令记成解析失败）。 */
    val url: String? = null,

    /** Authorization / x-api-key 提取的明文密钥；没有则为 null。 */
    val apiKey: CharArray? = null,

    /** body 里的 `model` 字段；不是字符串或没有 body 时为 null。 */
    val model: String? = null,

    /** `-A/--user-agent` 提取的 UA；没有则 null（预览页让用户补）。 */
    val userAgent: String? = null,

    /** `-X/--request` 提取的方法（如 POST）。仅供参考，不参与预设落库。 */
    val method: String?,

    /** 保留下来的有序请求头（已剔除代码接管的那些）。 */
    val headers: List<Pair<String, String>>,

    /** 从 body 推断出的 merge patch；没有可保留字段时为 `"{}"`。 */
    val bodyPatch: String,

    /** 原始 data body。客户端预设只吃 bodyPatch，供应商导入还要读 model。 */
    val dataBody: String? = null,

    /** 被自动剔除的请求头键，供预览页"剔除了哪些"告知。 */
    val droppedHeaders: List<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CurlResult) return false
        return url == other.url &&
            apiKey.contentEquals(other.apiKey) &&
            model == other.model &&
            userAgent == other.userAgent &&
            method == other.method &&
            headers == other.headers &&
            bodyPatch == other.bodyPatch &&
            dataBody == other.dataBody &&
            droppedHeaders == other.droppedHeaders
    }

    override fun hashCode(): Int {
        var result = url?.hashCode() ?: 0
        result = 31 * result + (apiKey?.contentHashCode() ?: 0)
        result = 31 * result + (model?.hashCode() ?: 0)
        result = 31 * result + (userAgent?.hashCode() ?: 0)
        result = 31 * result + (method?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + bodyPatch.hashCode()
        result = 31 * result + (dataBody?.hashCode() ?: 0)
        result = 31 * result + droppedHeaders.hashCode()
        return result
    }
}

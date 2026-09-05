package com.lc33.tokenvault.domain

/**
 * 鉴权头风格。
 *
 * [AUTO] 是默认值，它的重试是**双向**的（§5.2，M0.5 实测修正过一次）：
 * - Anthropic 协议先发 `x-api-key`，被 401/403 拒了再试 `Authorization: Bearer`；
 * - 反方向也要试——Agent Router 的客户端校验**只挂在 Bearer 那条路径上**，
 *   同一个 UA 用 `x-api-key` 直接 200、用 Bearer 就 401 `unauthorized client detected`。
 *   所以撞客户端闸时先换鉴权头（一次请求），再考虑换客户端预设（最多四次）。
 *
 * 成功之后把结果固化到 `providers.authStyle`，下次不再试第二种。
 */
enum class AuthStyle {
    AUTO,
    BEARER,
    X_API_KEY,
}

/**
 * 协议（`domain/Protocol.kt`，§5.2 的那张表）。
 *
 * 红线 18：**协议属于模型**，能力集合属于供应商，二者不能互相推导。
 * `provider.supportedProtocols` 决定"能拉哪些模型列表、界面上给哪些选项"，
 * `model.protocol` 决定"这个模型该发到哪条路径、用哪种 body"。
 * `示例数据.md` 里 Agent Router 支持三种协议，但 `claude-opus-5` 只能走 Anthropic、
 * `gpt-5.6-sol` 只能走 Responses——这就是为什么不能只存一处。
 *
 * @param wireName 入库与备份包里的稳定标识。**不要用 [name]**：枚举改名会静默改变
 *   已存数据的含义，而 CSV 形式的 `supportedProtocols` 列没有版本号可以据此迁移。
 * @param defaultPathTemplate `{ver}` 由规范化算法（§5.2 第 4 步）填。
 *   用户可以在 `providers.pathOverrides` 里覆盖它——DeepSeek 的 Anthropic 端点在
 *   `/anthropic` 下，而 M0.5 实测猜错路径时上游返回 404 且**响应体完全为空**，
 *   所以"猜"这条路本来就走不通。
 */
enum class Protocol(
    val wireName: String,
    val defaultPathTemplate: String,
    val defaultAuthStyle: AuthStyle,
) {
    CHAT("chat", "/{ver}/chat/completions", AuthStyle.BEARER),
    RESPONSES("responses", "/{ver}/responses", AuthStyle.BEARER),
    ANTHROPIC("anthropic", "/{ver}/messages", AuthStyle.X_API_KEY),
    ;

    companion object {
        fun fromWireName(value: String): Protocol? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }

        /**
         * 文本导入用的别名解析（§11.1）：忽略大小写、空格与括号内容。
         *
         * `示例数据.md` 里写的是 `Chat Completions` / `Responses (原生)` / `Anthropic Messages`，
         * 而用户手写时什么都可能——所以这里放宽，但**只放宽到能唯一确定协议**为止。
         */
        fun fromAlias(raw: String): Protocol? {
            val v = raw.lowercase().replace(ALIAS_NOISE, "")
            return when {
                v.startsWith("chatcompletions") || v == "chat" || v == "openai" -> CHAT
                v.startsWith("responses") -> RESPONSES
                v.contains("anthropic") || v.contains("claude") || v == "messages" -> ANTHROPIC
                else -> null
            }
        }

        /**
         * new-api 的 `supported_endpoint_types` → 协议集合。
         *
         * **返回集合而不是单个值**，这是 M0.5 实测逼出来的：`gpt-5.6-sol` 的该字段是
         * `["openai"]`，而它实际是在 **Responses** 上探测成功的。也就是说 `openai` 的含义
         * 是"OpenAI 兼容"，同时覆盖 chat 与 responses，不是单指 `/chat/completions`。
         * 按单值解析会把一半模型的协议标错，而标错的表现是 404，很难反推回这里。
         */
        fun protocolsForEndpointType(raw: String): Set<Protocol> =
            when (raw.trim().lowercase()) {
                "openai" -> setOf(CHAT, RESPONSES)
                "anthropic" -> setOf(ANTHROPIC)
                else -> emptySet()
            }

        // 全角括号与"原生"是拿来匹配**用户粘贴进来的中文**的（`Responses（原生）`），
        // 不是给用户看的文案——搬进 strings.xml 会让它跟着界面语言变，于是"手机设成
        // 英文的用户粘贴一段中文数据"就解析不出来。
        private val ALIAS_NOISE = Regex("""[\s()（）_-]|原生|native""") // i18n-exempt: 解析模式
    }
}

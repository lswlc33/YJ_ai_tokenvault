package com.lc33.tokenvault.data.seed

import com.lc33.tokenvault.domain.Protocol
import com.lc33.tokenvault.domain.model.ClientProfile

/**
 * 8 个内置客户端伪装预设的内容（计划.md §8.2 那张表）。
 *
 * **为什么内容在这里、而不塞进探测代码**：红线 22 说"预设是数据不是代码"，探测代码里
 * 不允许硬编码任何 `User-Agent`。这些指纹是数据，权威来源是这张表；探测引擎要从
 * `client_profiles` 表读、从 `HeaderAssembler` 组装，而不是在这里 import。
 *
 * **诚实声明**（设置页说明文字里必须写）：这里的版本号与头部集合来自社区观察，
 * 没有官方文档，且随客户端升级漂移。它们是"能用的起点"，不是权威值。可靠路径是
 * cURL 导入（抓一次真实客户端的包）。
 *
 * **`name` 是 ASCII 兜底值**：内置预设的本地化显示名由 UI 按 `builtinKey` 从 strings.xml
 * 映射（红线 19），这里存的 `name` 只保证"还没接 UI 映射时不至于空白"。品牌名本身
 * （Claude Code / Codex CLI / Cherry Studio）不分语言，所以直接存 ASCII；本应用自己的
 * `default` 存 `YuanJi (Default)`，中文名由 `profile_name_default` 资源给出。
 *
 * [BUILTIN_PRESETS] 是**纯数据**，抽出来是为了让 [ProfileSeeder] 可单测——种子内容
 * 是否正确、是否覆盖了 8 个 builtinKey，不该靠跑一次真库才知道。
 */
object BuiltinPresets {

    /** 种子轮号。升一个号，`seedBuiltin` 就会刷新所有"用户没改过"的内置条目。 */
    const val REV = 1

    /**
     * 8 个内置预设。`zcode` 留空位：没有可靠的指纹资料（需要用户提供一次抓包）。
     *
     * 「适用协议」是**排序提示，不是硬过滤**（M0.5 实测）：`claude_code` 是在 CHAT
     * 协议上过了 Agent Router 的闸的，中转站的闸只看请求头、不看路由。
     */
    val BUILTIN_PRESETS: List<ClientProfile> = listOf(
        ClientProfile(
            name = "YuanJi (Default)",
            builtinKey = "default",
            userAgent = "YuanJi/{app_version} (Android {android_release}; {arch})",
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC),
            builtinRev = REV,
            sortOrder = 0,
        ),
        ClientProfile(
            name = "Claude Code",
            builtinKey = "claude_code",
            userAgent = "claude-cli/1.0.119 (external, cli)",
            headers = listOf(
                "x-app" to "cli",
                "anthropic-beta" to "claude-code-20250219,oauth-2025-04-20",
                "x-stainless-lang" to "js",
                "x-stainless-runtime" to "node",
                "x-stainless-runtime-version" to "v22.14.0",
                "x-stainless-os" to "MacOS",
                "x-stainless-arch" to "arm64",
                "x-stainless-package-version" to "0.60.0",
                "x-stainless-retry-count" to "0",
            ),
            protocols = setOf(Protocol.ANTHROPIC, Protocol.CHAT),
            builtinRev = REV,
            sortOrder = 1,
        ),
        ClientProfile(
            name = "Codex CLI",
            builtinKey = "codex_cli",
            userAgent = "codex_cli_rs/0.44.0 (Mac OS 15.5.0; arm64) Apple_Terminal",
            headers = listOf(
                "originator" to "codex_cli_rs",
                "session_id" to "{uuid}",
                "openai-beta" to "responses=experimental",
            ),
            protocols = setOf(Protocol.RESPONSES),
            builtinRev = REV,
            sortOrder = 2,
        ),
        ClientProfile(
            name = "Anthropic SDK (Python)",
            builtinKey = "anthropic_sdk",
            userAgent = "Anthropic/Python 0.40.0",
            headers = listOf(
                "x-stainless-lang" to "python",
                "x-stainless-runtime" to "CPython",
                "x-stainless-runtime-version" to "3.12.3",
                "x-stainless-os" to "Linux",
                "x-stainless-arch" to "x64",
            ),
            protocols = setOf(Protocol.ANTHROPIC),
            builtinRev = REV,
            sortOrder = 3,
        ),
        ClientProfile(
            name = "OpenAI SDK (Python)",
            builtinKey = "openai_sdk",
            userAgent = "OpenAI/Python 1.60.0",
            headers = listOf(
                "x-stainless-lang" to "python",
                "x-stainless-runtime" to "CPython",
                "x-stainless-runtime-version" to "3.12.3",
                "x-stainless-os" to "Linux",
                "x-stainless-arch" to "x64",
            ),
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES),
            builtinRev = REV,
            sortOrder = 4,
        ),
        ClientProfile(
            name = "Gemini CLI",
            builtinKey = "gemini_cli",
            userAgent = "GeminiCLI/0.1.12 (darwin; arm64)",
            headers = listOf("x-goog-api-client" to "gl-node/22.14.0"),
            protocols = setOf(Protocol.CHAT),
            builtinRev = REV,
            sortOrder = 5,
        ),
        ClientProfile(
            name = "Cherry Studio",
            builtinKey = "cherry_studio",
            userAgent = "CherryStudio/1.4.0 (Windows NT 10.0; x64)",
            protocols = setOf(Protocol.CHAT, Protocol.RESPONSES, Protocol.ANTHROPIC),
            builtinRev = REV,
            sortOrder = 6,
        ),
        // zcode：没有可靠的指纹资料，留空位。用户可 cURL 导入自补，或后续抓包后升级 REV。
        ClientProfile(
            name = "ZCode",
            builtinKey = "zcode",
            userAgent = "",
            protocols = emptySet(),
            builtinRev = REV,
            sortOrder = 7,
        ),
    )
}

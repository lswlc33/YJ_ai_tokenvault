package com.lc33.tokenvault.importer

import com.lc33.tokenvault.domain.Protocol
import kotlin.text.RegexOption.IGNORE_CASE

/**
 * cURL 供应商导入：把一段或多段 `curl` 命令转成待入库的供应商记录。
 *
 * 与 [CurlParser] 的分工：CurlParser 负责“一条命令”的词法与字段，这里负责
 * “多段粘贴”的切分、按 API 根地址合并、生成用户可读的供应商名。两者都保持纯函数。
 *
 * 目前只提取 cURL 里确定可靠的信息：请求 URL、鉴权头中的 Key、body 的 model、
 * 端点路径隐含的协议。余额配置与平台账号 cURL 里没有，导入后由用户补。
 */
object CurlImporter {

    private val COMMAND_START = Regex("""^\s*(?:\$\s+)?curl(?:\.exe)?\b""", IGNORE_CASE)
    private val URL_SHAPE = Regex("""^(https?)://([^/?#]+)([^?#]*)([?#].*)?$""", IGNORE_CASE)

    /** 这些路径后缀能唯一反推协议；`/models` 只用于剥掉列表路径。 */
    private val ENDPOINT_SUFFIXES = listOf(
        "/chat/completions" to Protocol.CHAT,
        "/responses" to Protocol.RESPONSES,
        "/messages" to Protocol.ANTHROPIC,
    )

    fun parse(text: String): ParseResult {
        val records = mutableListOf<ParsedRecord>()
        val errors = mutableListOf<ParseError>()
        val drafts = LinkedHashMap<String, MutableDraft>()

        for (command in splitCommands(text)) {
            val parsed = CurlParser.parse(command)
            val url = parsed.url
            if (url == null) {
                errors += ParseError(null, "no url")
                continue
            }
            val key = parsed.apiKey
            if (key == null) {
                errors += ParseError(null, "no key")
                continue
            }

            val endpoint = endpointOf(url)
            val draft = drafts.getOrPut(endpoint.baseUrl) { MutableDraft(endpoint) }
            draft.issues += endpoint.issues
            endpoint.protocol?.let { draft.protocols += it }
            parsed.model?.let { modelId ->
                val protocol = endpoint.protocol ?: Protocol.CHAT
                draft.protocols += protocol
                draft.models.getOrPut(modelId) {
                    ParsedModel(modelId, protocol, needsReview = needsReview(modelId))
                }
            }
            if (draft.keys.none { it.secret.contentEquals(key) }) {
                draft.keys += ParsedKey(keyLabel(draft.keys.size), key)
            }
        }

        val usedNames = mutableMapOf<String, Int>()
        for (draft in drafts.values) {
            if (draft.keys.isEmpty()) continue
            val baseName = providerNameOf(draft.host)
            // Kotlin/Native 没有 MutableMap.merge；这里手写计数，保持 common 代码零平台分支。
            val seen = usedNames[baseName] ?: 0
            usedNames[baseName] = seen + 1
            val name = if (seen == 0) baseName else "$baseName ${seen + 1}"
            val modelIssues = draft.models.values
                .filter { it.needsReview }
                .map { ImportIssue.MODEL_NAME_REVIEW }
            records += ParsedRecord(
                name = name,
                websiteUrl = draft.origin,
                apiBaseUrl = draft.baseUrl,
                supportedProtocols = draft.protocols,
                keys = draft.keys,
                models = draft.models.values.toList(),
                issues = (draft.issues + modelIssues).distinct(),
            )
        }

        return ParseResult(records = records, errors = errors)
    }

    // ------------------------------------------------------------------ 命令切分

    /**
     * 按行扫描：`curl` 开头开新命令，空行结束当前命令。
     *
     * 不先全局替换续行符，是因为多段命令可能没有空行分隔；逐行看 `curl` 开头才能
     * 区分“下一段命令”和“上一段的续行”。
     */
    private fun splitCommands(text: String): List<String> {
        val commands = mutableListOf<String>()
        val current = StringBuilder()
        var inCommand = false

        fun flush() {
            val command = current.toString().trim()
            if (command.isNotEmpty()) commands += command
            current.clear()
            inCommand = false
        }

        for (line in text.lineSequence()) {
            when {
                line.isBlank() && inCommand -> flush()
                COMMAND_START.containsMatchIn(line) -> {
                    flush()
                    inCommand = true
                    current.appendLine(line)
                }
                inCommand -> current.appendLine(line)
                else -> Unit
            }
        }
        flush()
        return commands
    }

    // ------------------------------------------------------------------ 端点

    private data class Endpoint(
        val baseUrl: String,
        val origin: String,
        val host: String,
        val protocol: Protocol?,
        val issues: List<ImportIssue>,
    )

    private fun endpointOf(url: String): Endpoint {
        val match = URL_SHAPE.matchEntire(url.trim())
            ?: return Endpoint(url.trim(), url.trim(), url.trim(), null, listOf(ImportIssue.BAD_ENDPOINT))

        val scheme = match.groupValues[1].lowercase()
        val hostWithPort = match.groupValues[2]
        val path = match.groupValues[3]
        val hasQueryOrFragment = match.groupValues[4].isNotEmpty()
        val host = hostWithPort.substringBefore(':').removePrefix("[").removeSuffix("]")
        val origin = "$scheme://$hostWithPort"

        val matched = ENDPOINT_SUFFIXES.firstOrNull { (suffix, _) ->
            path.equals(suffix, ignoreCase = true) || path.endsWith(suffix, ignoreCase = true)
        }
        val modelsList = path.endsWith("/models", ignoreCase = true)
        val strippedPath = when {
            matched != null -> path.substring(0, path.length - matched.first.length)
            modelsList -> path.substring(0, path.length - "/models".length)
            else -> path
        }.trimEnd('/')
        // query / fragment 原样保留：这类地址本来就不受支持，静默丢掉会得到一个
        // “看起来能导入、实际永远失败”的端点；预览里的 BAD_ENDPOINT 会让用户去改。
        val suffix = match.groupValues[4]
        val baseUrl = if (strippedPath.isEmpty()) origin + suffix else origin + strippedPath + suffix

        val issues = buildList {
            if (hasQueryOrFragment) add(ImportIssue.BAD_ENDPOINT)
            if (scheme == "http") add(ImportIssue.INSECURE_ENDPOINT)
        }
        return Endpoint(baseUrl, origin, host, matched?.second, issues)
    }

    /** `iceberg.tiktok.vip` → `tiktok`；没有足够标签时退回 host 本身。 */
    private fun providerNameOf(host: String): String {
        val labels = host.split('.').filter { it.isNotEmpty() }
        return labels.getOrNull(labels.size - 2) ?: host
    }

    /** 与文本导入同一规则：模型 id 含空格或大写时需要用户复核。 */
    private fun needsReview(modelId: String): Boolean =
        modelId.any { it.isWhitespace() || it.isUpperCase() }

    private fun keyLabel(index: Int): String =
        if (index == 0) "主号" else "备用 ${index + 1}" // i18n-exempt: 导入格式规定的密钥 label（§11.1）

    private class MutableDraft(endpoint: Endpoint) {
        val baseUrl = endpoint.baseUrl
        val origin = endpoint.origin
        val host = endpoint.host
        val protocols = LinkedHashSet<Protocol>()
        val models = LinkedHashMap<String, ParsedModel>()
        val keys = mutableListOf<ParsedKey>()
        val issues = mutableListOf<ImportIssue>()
    }
}

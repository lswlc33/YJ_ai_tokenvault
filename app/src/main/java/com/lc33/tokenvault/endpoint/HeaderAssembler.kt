package com.lc33.tokenvault.endpoint

import com.lc33.tokenvault.domain.model.ClientProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 组装结果。`headers` 是最终要发出去的有序头。
 *
 * [warnedAuthKeys] 是"预设里出现了本该由协议代码控制的鉴权头键"，被忽略掉的那些。
 * 调用方据此记一条 warn 日志——这是防用户从 cURL 导入时把别人的密钥一起带进来（§8.2 第 4 步）。
 */
data class AssembledHeaders(
    val headers: List<Pair<String, String>>,
    val warnedAuthKeys: List<String> = emptyList(),
)

/**
 * 客户端伪装预设的头组装（计划.md §8.2）。**纯函数，可单测**。
 *
 * 组装顺序（顺序不能变）：
 * 1. 基础头 `Accept` / `Content-Type`。
 * 2. 预设头按声明顺序覆盖 / 追加；`User-Agent` 取预设值。
 * 3. **鉴权头最后加，且预设不得覆盖**：`Authorization` / `x-api-key` / `anthropic-version`
 *    由协议代码控制，预设里若出现这些键就忽略并记一条 warn（红线 22）。
 * 4. 展开占位符。
 *
 * 占位符与 bodyPatch 的合并在 [expandPlaceholders] / [mergeBodyPatch] 里，拆成独立函数
 * 是为了让它们各自有单测，且 [assemble] 只做"顺序"这一件事。
 */
object HeaderAssembler {

    /** 协议代码控制的鉴权头键。预设里出现这些键一律忽略（§8.2 第 4 步）。 */
    private val AUTH_HEADER_KEYS = setOf("authorization", "x-api-key", "anthropic-version")

    /**
     * 组装最终头列表。
     *
     * @param baseHeaders 基础头（`Accept` / `Content-Type`）。**不参与占位符展开**——它们不含占位符。
     * @param profile 客户端预设。null 表示无预设，只拼基础头 + 鉴权头。
     * @param authHeaders 协议代码构造的鉴权头，最后加、不可被预设覆盖。
     * @param placeholders 占位符值（`app_version` / `android_release` / `arch` / `uuid` 等），
     *   由调用方注入（红线 20：这些是平台能力，纯函数不自己读）。
     *   同一请求内 `{uuid}` 取同一个值这件事，由调用方把同一个值放进这个 map 来保证。
     */
    fun assemble(
        baseHeaders: List<Pair<String, String>>,
        profile: ClientProfile?,
        authHeaders: List<Pair<String, String>>,
        placeholders: Map<String, String> = emptyMap(),
    ): AssembledHeaders {
        // 顺序敏感：用 LinkedHashMap 保序，重复键后写的覆盖先写的（第 2 步"按序覆盖"）。
        val merged = LinkedHashMap<String, String>()
        baseHeaders.forEach { (k, v) -> merged[k] = v }

        val warned = mutableListOf<String>()
        if (profile != null) {
            // User-Agent 取预设值。
            merged["User-Agent"] = profile.userAgent
            for ((key, value) in profile.headers) {
                if (key.lowercase() in AUTH_HEADER_KEYS) {
                    warned += key
                    continue
                }
                merged[key] = value
            }
        }

        // 鉴权头最后加（第 3 步）：直接覆盖预设里侥幸留下的同名键。
        authHeaders.forEach { (k, v) -> merged[k] = v }

        // 展开占位符（第 4 步）。
        val expanded = merged.map { (k, v) -> k to expandPlaceholders(v, placeholders) }

        return AssembledHeaders(headers = expanded, warnedAuthKeys = warned)
    }

    /**
     * 展开占位符。
     *
     * [values] 里放的是**已经算好**的占位符值；`{random_hex:N}` 与 `{uuid}`（若 values 里没有）
     * 每次现算，由 [randomHex] 提供随机源（注入，红线 20）。同一请求内 `{uuid}` 取同一值
     * 靠调用方把同一个值放进 values——展开本身不保证跨调用一致。
     */
    fun expandPlaceholders(
        template: String,
        values: Map<String, String>,
        randomHex: (Int) -> String = { n -> defaultRandomHex(n) },
    ): String {
        // 先处理带参数的 {random_hex:N}，再处理普通 {key}。
        var out = RANDOM_HEX_REGEX.replace(template) { match ->
            val n = match.groupValues[1].toIntOrNull() ?: 16
            randomHex(n)
        }
        out = PLACEHOLDER_REGEX.replace(out) { match ->
            val key = match.groupValues[1]
            values[key] ?: match.value
        }
        return out
    }

    private val PLACEHOLDER_REGEX = Regex("""\{([a-z_]+)}""")
    private val RANDOM_HEX_REGEX = Regex("""\{random_hex:(\d+)}""")

    private fun defaultRandomHex(n: Int): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder(n)
        repeat(n) { sb.append(chars.random()) }
        return sb.toString()
    }
}

private val mergeJson = Json { ignoreUnknownKeys = true }

/**
 * RFC 7386 JSON merge patch 合并：把 [patch] 叠到 [baseBody] 上。
 *
 * 语义：patch 里的 `null` 值表示**删除**该键；对象递归合并；其余类型直接覆盖。
 * 解析失败（[baseBody] 不是合法 JSON）时返回原 [baseBody]——极简探测 body 是代码构造的、
 * 一定是合法 JSON，这里容错只是不把"叠 patch"变成崩溃点（红线 8 的反面：不因可恢复的
 * 输入抛异常）。
 */
fun mergeBodyPatch(baseBody: String, patch: String): String {
    if (patch.isBlank() || patch == "{}") return baseBody
    // 探测 body 是代码构造的对象，一定是合法 JSON 对象；但容错立场一致：解析失败就原样返回，
    // 不把"叠 patch"变成崩溃点（红线 8 的反面）。
    val base = runCatching { mergeJson.parseToJsonElement(baseBody).jsonObject }.getOrNull()
        ?: return baseBody
    val patchElement = runCatching { mergeJson.parseToJsonElement(patch) }.getOrNull()
        ?: return baseBody
    // RFC 7386 顶层必须是对象：patch 不是对象时语义无定义，直接放弃。
    if (patchElement !is JsonObject) return baseBody
    val merged = mergeObjects(base, patchElement)
    return merged.toString()
}

private fun mergeObjects(base: JsonObject, patch: JsonObject): JsonObject {
    val result = base.toMutableMap()
    for ((key, patchValue) in patch) {
        when {
            patchValue is JsonNull -> result.remove(key)
            key in result && result[key] is JsonObject && patchValue is JsonObject ->
                result[key] = mergeObjects(result[key] as JsonObject, patchValue)
            else -> result[key] = patchValue
        }
    }
    return JsonObject(result)
}

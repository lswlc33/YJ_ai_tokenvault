package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * 任意站的 JSON 余额适配器（§9.2）。
 *
 * 用户在 `settings.balanceConfig` 里配 method / path / headers / valuePath / usedPath /
 * currency，适配器按 JSON 路径取值。**不支持表达式**（红线 14 的诚实声明：UI 提示里
 * 不许写"支持运算表达式"）。
 *
 * JSON 路径语法：`a.b.c`（逐级对象）、`arr[0]`（数组下标）。只有这两种，没有通配符、
 * 没有过滤、没有算术。
 *
 * 与其它适配器不同，它**带状态**（[config]）：config 来自 `settings.balanceConfig`，
 * 而 [BalanceAdapter.parse] 的签名拿不到 provider。所以 [BalanceRegistry] 每次查询都
 * **按 config 现造一个新实例**，而不是共享一个单例——这样 parse 签名不用改，也不会有
 * "一个实例的 config 被另一个供应商污染"的并发问题。
 */
class CustomJsonAdapter(
    private val config: JsonObject = JsonObject(emptyMap()),
) : BalanceAdapter {

    override val kind: BalanceKind = BalanceKind.CUSTOM_JSON

    override fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val base = settings.balanceBaseUrl?.trimEnd('/') ?: settings.apiRoot.trimEnd('/')
        val path = normalizePath(configString("path"))
        val method = configString("method")?.uppercase() ?: "GET"

        val headers = mutableListOf<Pair<String, String>>()
        when (val raw = config["headers"]) {
            null -> Unit
            is JsonObject -> raw.forEach { (k, v) ->
                // 头的值必须是标量。配成对象 / 数组时旧写法 `v.jsonPrimitive` 抛的是
                // ClassCastException 族（IllegalArgumentException），它不带 kind、不进
                // BalanceParseException 的"只报字段名"通道，一路冒到调用方。这里改成
                // 明确的解析失败，消息只写字段名（红线 32）。
                val value = (v as? JsonPrimitive)?.contentOrNull
                    ?: throw BalanceParseException(kind, "bad_header_value_$k")
                headers += k to value
            }
            else -> throw BalanceParseException(kind, "bad_headers_config")
        }
        // 默认 Key 的鉴权头兜底（配置里没显式带 Authorization 时才加）。
        if (headers.none { it.first.equals("Authorization", ignoreCase = true) }) {
            defaultKey?.let { headers += "Authorization" to "Bearer ${it.concatToString()}" }
        }

        return ProbeRequest(
            method = method,
            url = "$base$path",
            headers = headers,
            protocol = null,
        )
    }

    /**
     * 补上缺失的前导 `/`。
     *
     * 用户在配置框里写 `api/user/self` 是很自然的（浏览器地址栏就长这样），但直接串起来
     * 会得到 `https://hostapi/user/self` —— 一个看着像 404、实际是路径粘错了的请求，
     * 用户只会得到"余额总是查不到"。多补一个斜杠没有副作用：已经带 `/` 的原样通过。
     */
    private fun normalizePath(path: String?): String = when {
        path.isNullOrEmpty() -> ""
        path.startsWith("/") -> path
        else -> "/$path"
    }

    /** 配置项取值：只认标量，缺失或形状不对返回 null（由调用方决定要不要报错）。 */
    private fun configString(key: String): String? = (config[key] as? JsonPrimitive)?.contentOrNull

    override fun parse(status: Int, body: String): BalanceSnapshot {
        if (status !in 200..299) {
            throw BalanceParseException(kind, "http $status")
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw BalanceParseException(kind, "no_json")

        val valuePath = configString("valuePath")
            ?: throw BalanceParseException(kind, "missing_valuePath")
        // `doubleOrNull` 对 JSON number 与字符串两种写法都吃（`"12.3"` 与 `12.3`），
        // 上游这类字段经常是字符串，别把它判成解析失败。
        val amount = (resolvePath(root, valuePath) as? JsonPrimitive)?.doubleOrNull
            ?: throw BalanceParseException(kind, "missing_value_at_$valuePath")

        val used = configString("usedPath")?.let {
            (resolvePath(root, it) as? JsonPrimitive)?.doubleOrNull
        }
        val currency = configString("currency")?.takeIf { it.isNotBlank() }
            ?: BalanceSnapshot.UNKNOWN_CURRENCY

        return BalanceSnapshot(
            amount = amount,
            used = used,
            currency = currency,
            raw = body,
        )
    }

    /**
     * 解析 `a.b.c` 与 `arr[0]` 形式的路径。取不到返回 null（不是异常——由调用方决定
     * 是否够格抛 [BalanceParseException]）。
     */
    fun resolvePath(root: JsonElement, path: String): JsonElement? {
        var current: JsonElement = root
        for (segment in path.split('.')) {
            current = when {
                segment.contains('[') -> {
                    val name = segment.substringBefore('[')
                    val index = segment.substringAfter('[').substringBefore(']').toIntOrNull()
                        ?: return null
                    val arr = (current as? JsonObject)?.get(name) as? JsonArray ?: return null
                    arr.getOrNull(index) ?: return null
                }
                else -> (current as? JsonObject)?.get(segment) ?: return null
            }
        }
        return current
    }

    private val json = Json { ignoreUnknownKeys = true }
}

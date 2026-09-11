package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val path = config["path"]?.jsonPrimitive?.content ?: ""
        val method = config["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"

        val headers = mutableListOf<Pair<String, String>>()
        config["headers"]?.jsonObject?.forEach { (k, v) ->
            headers += k to v.jsonPrimitive.content
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

    override fun parse(status: Int, body: String): BalanceSnapshot {
        if (status !in 200..299) {
            throw BalanceParseException(kind, "http $status")
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw BalanceParseException(kind, "no_json")

        val valuePath = config["valuePath"]?.jsonPrimitive?.content
            ?: throw BalanceParseException(kind, "missing_valuePath")
        val amount = resolvePath(root, valuePath)?.jsonPrimitive?.doubleOrNull
            ?: throw BalanceParseException(kind, "missing_value_at_$valuePath")

        val used = config["usedPath"]?.jsonPrimitive?.content?.let {
            resolvePath(root, it)?.jsonPrimitive?.doubleOrNull
        }
        val currency = config["currency"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
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
                    val arr = current.jsonObject[name]?.jsonArray ?: return null
                    arr.getOrNull(index) ?: return null
                }
                else -> current.jsonObject[segment] ?: return null
            }
        }
        return current
    }

    private val json = Json { ignoreUnknownKeys = true }
}

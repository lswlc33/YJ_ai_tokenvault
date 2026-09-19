package com.lc33.tokenvault.balance

import com.lc33.tokenvault.domain.BalanceKind
import com.lc33.tokenvault.domain.model.BalanceSnapshot
import com.lc33.tokenvault.domain.model.KeySettings
import com.lc33.tokenvault.endpoint.ProbeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * OpenRouter / SiliconFlow / Moonshot 三个"复用默认 Key、单字段"的余额适配器。
 *
 * 三者结构相同：`GET {path}` + Bearer 默认 Key，从一个字段读出可用余额，币种固定。
 * 区别只在 path / 字段名 / 币种，所以用一个参数化的 [SimpleBalanceAdapter] 承载，
 * 而不是复制三份几乎一样的代码（§9.2 里那三个字段名以公开文档为依据，M7 要逐个实测，
 * 实测不过就删预设）。
 */
class SimpleBalanceAdapter(
    override val kind: BalanceKind,
    private val path: String,
    private val field: String,
    private val currency: String,
) : BalanceAdapter {

    override fun buildRequest(settings: KeySettings, defaultKey: CharArray?, token: CharArray?): ProbeRequest {
        val root = settings.apiRoot.trimEnd('/')
        // path 里 {ver} 用 settings.apiVersion 替换。
        val resolved = path.replace("{ver}", settings.apiVersion)
        val headers = mutableListOf<Pair<String, String>>()
        defaultKey?.let { headers += "Authorization" to "Bearer ${it.concatToString()}" }
        return ProbeRequest(
            method = "GET",
            url = "$root/$resolved",
            headers = headers,
            protocol = null,
        )
    }

    override fun parse(status: Int, body: String): BalanceSnapshot {
        if (status !in 200..299) {
            throw BalanceParseException(kind, "http $status")
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw BalanceParseException(kind, "no_json")
        // 字段层级有两种公开写法：顶层（`{"totalBalance": "12.3"}`）与包在 `data` 里
        // （`{"data": {"totalBalance": …}}`）。SiliconFlow 公开文档给的是后者，而旧实现
        // 只读顶层，于是那条查询永远以 `missing_totalBalance` 失败。
        // 顺序是"先顶层、再下钻 data 一层"：顶层能命中的既有供应商一律不受影响，
        // 只多认一种包裹形状。**两种形状都未用真实账号实测**（§9.2，M7 实测不过就删预设）。
        val located = root[field] ?: (root["data"] as? JsonObject)?.get(field)
        // `doubleOrNull` 同时吃 JSON number 与字符串两种写法（`12.3` / `"12.3"`）。
        val amount = (located as? JsonPrimitive)?.doubleOrNull
            ?: throw BalanceParseException(kind, "missing_$field")

        return BalanceSnapshot(
            amount = amount,
            used = null,
            currency = currency,
            raw = body,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
}

/**
 * 内置适配器的工厂。
 *
 * 字段名以公开文档为依据、**未用真实账号验证**（§9.2）。M7 逐个实测，实测不通过就
 * 从 [BalanceRegistry] 删掉对应预设而不是留着——留一个永远返回 0 的预设比没有更糟。
 */
object BuiltinBalanceAdapters {
    fun siliconflow() = SimpleBalanceAdapter(
        kind = BalanceKind.SILICONFLOW,
        path = "{ver}/user/info",
        field = "totalBalance",
        currency = "CNY",
    )

    fun moonshot() = SimpleBalanceAdapter(
        kind = BalanceKind.MOONSHOT,
        path = "{ver}/users/me/balance",
        field = "available_balance",
        currency = "CNY",
    )
}

package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一次 `/models` 解析的三种结局。
 *
 * 原来这里只有"有列表"和"null"两种，而**空列表**被当成合法业务结果往下传——于是
 * `{"data":[]}`（或 body 里条目全都没有 `id`）会走到 `ModelRepository.applyDiscovered`
 * 的"消失即删"那一条，把这家这把 Key 在本协议下发现过的模型**全清掉**。
 *
 * 为什么必须区分：一家站点的模型列表为空几乎总是异常（登录网关回了一个不像列表的 JSON、
 * 中转站把 `data` 换了名字、上游正在发布），而"这家真的一个模型都没有"在中转站上基本
 * 不成立——密钥能用就至少能列出一个。两者的代价完全不对称：误删的代价是用户手敲过又
 * 被发现的模型整片消失、下一轮再重新插入（`sortOrder` 一起被打乱）；不删的代价只是
 * 几行陈旧数据留在库里。
 *
 * 所以判定放在**解析层**（这里），仓库层不动：`applyDiscovered` 只在
 * [ModelListParse.Confirmed] 时被调用（见 `engine/ProbeEngine` 的调用点）。
 */
sealed interface ModelListParse {

    /** 至少解析出一条带 `id` 的模型。这才是"上游确实把列表给了我们"，可以做三路合并。 */
    data class Confirmed(val models: List<NewDiscoveredModel>) : ModelListParse

    /**
     * `data` 数组在，但一条带 id 的模型都没有（`[]` 本身，或所有条目都缺 `id`）。
     *
     * 形态上像列表、内容上不可信：**不动库**。
     */
    data object SuspiciousEmpty : ModelListParse

    /** 根本不是模型列表（空 body、不是 JSON、没有 `data` 数组）。**不动库**。 */
    data object Unparseable : ModelListParse
}

/**
 * OpenAI 兼容 `/models` 响应 → 发现模型（§8.3 的三路合并输入）。
 */
object ModelListParser {

    fun parse(body: String?, fallbackProtocol: Protocol): ModelListParse {
        if (body.isNullOrBlank()) return ModelListParse.Unparseable
        val data = runCatching {
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
        }.getOrNull() ?: return ModelListParse.Unparseable

        val result = mutableListOf<NewDiscoveredModel>()
        for (item in data) {
            val objectItem = runCatching { item.jsonObject }.getOrNull() ?: continue
            val modelId = runCatching {
                objectItem["id"]?.jsonPrimitive?.content
            }.getOrNull()?.trim().orEmpty()
            if (modelId.isEmpty()) continue

            val endpointTypes = runCatching {
                objectItem["supported_endpoint_types"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            }.getOrNull().orEmpty()

            val protocols = if (endpointTypes.isEmpty()) {
                setOf(fallbackProtocol)
            } else {
                endpointTypes.flatMapTo(mutableSetOf()) { raw ->
                    Protocol.protocolsForEndpointType(raw)
                }
            }
            protocols.forEach { protocol -> result += NewDiscoveredModel(modelId, protocol) }
        }

        val distinct = result.distinctBy { it.modelId to it.protocol }
        return if (distinct.isEmpty()) ModelListParse.SuspiciousEmpty else ModelListParse.Confirmed(distinct)
    }
}

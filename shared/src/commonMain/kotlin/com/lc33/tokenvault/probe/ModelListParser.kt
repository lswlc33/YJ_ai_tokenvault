package com.lc33.tokenvault.probe

import com.lc33.tokenvault.domain.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI 兼容 `/models` 响应 → 发现模型。
 *
 * 解析失败返回 null 而不是空列表：空列表是合法业务结果（上游真没有模型），要触发
 * “消失即停用”；解析失败则说明这份 body 根本不是模型列表，绝不能拿它清掉旧数据。
 */
object ModelListParser {

    fun parse(body: String?, fallbackProtocol: Protocol): List<NewDiscoveredModel>? {
        if (body.isNullOrBlank()) return null
        val data = runCatching {
            Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
        }.getOrNull() ?: return null

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
        return result.distinctBy { it.modelId to it.protocol }
    }
}

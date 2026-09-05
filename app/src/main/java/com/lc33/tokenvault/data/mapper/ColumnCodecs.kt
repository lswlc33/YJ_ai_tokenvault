package com.lc33.tokenvault.data.mapper

import com.lc33.tokenvault.domain.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 实体的原始列 ↔ 领域类型。
 *
 * 这一层是**唯一发生转换的地方**（CLAUDE.md 要求实体与 domain 是两套类、中间有显式映射器）。
 * 单独抽出来还有一个理由：CSV 与 JSON 列的往返是最容易静默损坏数据的地方——
 * 存进去与读出来差一个字符，表现是"某个供应商的协议莫名少了一个"，而没有任何报错。
 * 所以它们都有单测。
 *
 * 统一的容错立场：**读的时候遇到不认识的值就跳过，不抛**。抛的后果是整个列表页打不开
 * （一行坏数据毁掉全部），跳过的后果是那一行少一个协议——后者可恢复，前者不可。
 * 但跳过必须是**读**方向的单向容错：写进去的一定是我们认识的值。
 */
private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** `Set<Protocol>` → CSV。用 `wireName`，因为枚举改名不该改变已存数据的含义。 */
fun Set<Protocol>.toCsv(): String = joinToString(",") { it.wireName }

/**
 * CSV → `Set<Protocol>`。
 *
 * 保持**声明顺序**（`LinkedHashSet`）：协议在 UI 上是按顺序显示的 chips，
 * 每次读出来顺序不同会让列表看起来在闪。
 */
fun String.toProtocolSet(): Set<Protocol> =
    split(',')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { Protocol.fromWireName(it) }
        .toCollection(LinkedHashSet())

/** `Map<Protocol,String>` → JSON 对象。键是 `wireName`。 */
fun Map<Protocol, String>.pathOverridesToJson(): String =
    buildJsonObject {
        // 按枚举顺序写，这样同一份数据每次序列化出的字符串一致（diff 与"没变就不写"都靠它）
        Protocol.entries.forEach { protocol ->
            this@pathOverridesToJson[protocol]?.let { put(protocol.wireName, JsonPrimitive(it)) }
        }
    }.toString()

fun String.toPathOverrides(): Map<Protocol, String> = runCatching {
    json.parseToJsonElement(this).jsonObject.entries.mapNotNull { (key, value) ->
        val protocol = Protocol.fromWireName(key) ?: return@mapNotNull null
        val path = value.jsonPrimitive.contentOrNull ?: return@mapNotNull null
        protocol to path
    }.toMap()
}.getOrDefault(emptyMap())

/**
 * 有序请求头 → JSON **数组** of `[key, value]`。
 *
 * 不用 JSON 对象：部分上游会看请求头顺序，而 JSON 对象在不同解析器下顺序不保证（§6.2）。
 * 用数组之后顺序是数据的一部分，谁都改不掉。
 */
fun List<Pair<String, String>>.headersToJson(): String =
    buildJsonArray {
        forEach { (key, value) ->
            add(
                buildJsonArray {
                    add(JsonPrimitive(key))
                    add(JsonPrimitive(value))
                },
            )
        }
    }.toString()

fun String.toHeaderList(): List<Pair<String, String>> = runCatching {
    json.parseToJsonElement(this).jsonArray.mapNotNull { element ->
        val pair = element as? JsonArray ?: return@mapNotNull null
        if (pair.size != 2) return@mapNotNull null
        val key = pair[0].jsonPrimitive.contentOrNull ?: return@mapNotNull null
        val value = pair[1].jsonPrimitive.contentOrNull ?: return@mapNotNull null
        key to value
    }
}.getOrDefault(emptyList())

/** CSV 形式的模态列表（`model_catalog` 用）。 */
fun List<String>.toCsv(): String = joinToString(",")

fun String?.csvToList(): List<String> =
    this?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

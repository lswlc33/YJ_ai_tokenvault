package com.lc33.tokenvault.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * models.dev `api.json` 的投影视图。
 *
 * **这份文件只描述"我们看得懂的那部分上游数据"**，不是 api.json 的完整模型：
 * `reasoning_options`、`interleaved`、`experimental`、`cost.tiers`、`provider` 等字段一律
 * 不声明，由 [CatalogParser.json] 的 `ignoreUnknownKeys` 丢掉。故意不全解有两个理由：
 * 一是全量快照 4.7 MB，多声明一个字段就多一万份对象；二是上游加字段是常态
 * （实测 7,864 行里 `status` 只有 283 行有），跟着改会让 app 每次上游变更都要发版。
 *
 * 类型是按**实测**定的，不是猜的：`limit.context` / `limit.output` 在全部 7,864 行里都是数字，
 * `limit.input` 只有 1,308 行有，`cost.input`/`cost.output` 7,451 行，
 * `attachment`/`reasoning`/`tool_call`/`open_weights`/`temperature` 恒为布尔。
 *
 * **`limit.*` 与 `cost.*` 一律按 [Double] 收，再在解析时转成 Int**：上游哪天写出
 * `1000000.0` 这种带小数点的整数，直接声明成 `Int` 会让整次同步在解码那一步炸掉，
 * 而这是个在手机上很难复现的炸法。宽进严出比"相信上游永远是整数"划算。
 */
object ModelsDevSchema {
    /** 解码用的统一配置。`ignoreUnknownKeys` 是这份 DTO 能只声明一部分字段的唯一理由。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

/** 一家厂商（外层 key 那份）。models.dev 的顶层就是 `slug -> 这里` 的一个大 map。 */
@Serializable
data class ModelsDevProvider(
    /** 厂商 slug。与外层 key 实测一致，取外层 key 为准，这里只做校验用。 */
    val id: String = "",
    val name: String = "",

    /** 上游 API 根地址与文档地址，只用于展示，app 不会往这里发请求。 */
    val api: String? = null,
    val doc: String? = null,
    val npm: String? = null,
    val models: Map<String, ModelsDevModel> = emptyMap(),
)

@Serializable
data class ModelsDevModel(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val family: String? = null,
    val attachment: Boolean = false,
    val reasoning: Boolean = false,
    @SerialName("tool_call") val toolCall: Boolean = false,
    @SerialName("structured_output") val structuredOutput: Boolean = false,
    val temperature: Boolean = false,
    @SerialName("open_weights") val openWeights: Boolean = false,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
    val modalities: ModelsDevModalities? = null,
    val limit: ModelsDevLimit? = null,
    val cost: ModelsDevCost? = null,
    val status: String? = null,

    /** 训练知识截止，上游给的是 `2025-04` 这种串。 */
    val knowledge: String? = null,
)

/**
 * 上下文窗口。
 *
 * 刻意不用 [SerialName] 改名：上游就是 `context` / `input` / `output` 三个键。
 */
@Serializable
data class ModelsDevLimit(
    val context: Double? = null,
    val input: Double? = null,
    val output: Double? = null,
)

/**
 * 每百万 token 的美元价。
 *
 * 上游还有 `tiers`（分段计价）与 `context_over_200k`（超 200k 上下文另算价）两种结构，
 * 这里**都不解**。详情页因此只能显示基础价，所以模型页那一段要写明"不含阶梯计价"——
 * 把 4 美元的基础价摆在 32 美元档的模型旁边，比不显示更容易被当成上游数据错。
 */
@Serializable
data class ModelsDevCost(
    val input: Double? = null,
    val output: Double? = null,
    @SerialName("cache_read") val cacheRead: Double? = null,
    @SerialName("cache_write") val cacheWrite: Double? = null,
)

@Serializable
data class ModelsDevModalities(
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)
